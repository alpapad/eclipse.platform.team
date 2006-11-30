/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.ui.history;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.revisions.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.team.core.variants.IResourceVariant;
import org.eclipse.team.internal.ui.TeamUIMessages;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.history.FileRevisionEditorInput;
import org.eclipse.ui.*;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.internal.ide.IDEWorkbenchPlugin;
import org.eclipse.ui.internal.registry.EditorDescriptor;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Helper class that coordinates the selection behavior between an editor
 * revision ruler and a history list such as one shown in the history view. In
 * other words, the selection in the history list will be reflected in the
 * revision rule and vice versa.
 * <p>
 * <strong>EXPERIMENTAL</strong>. This class or interface has been added as
 * part of a work in progress. There is a guarantee neither that this API will
 * work nor that it will remain the same. Please do not use this API without
 * consulting with the Platform/Team team.
 * </p>
 * 
 * @see Revision
 * @see RevisionInformation
 * @since 3.3
 */
public abstract class RevisionAnnotationController {

	private final ISelectionProvider fRulerSelectionProvider;
	private final ISelectionProvider fHistoryListSelectionProvider;
	private final ISelectionChangedListener rulerListener = new ISelectionChangedListener() {
		public void selectionChanged(SelectionChangedEvent event) {
			ISelection selection= event.getSelection();
			Revision selected= null;
			if (selection instanceof IStructuredSelection)
				selected= (Revision) ((IStructuredSelection) selection).getFirstElement();
		
			if (selected == null)
				return;
		
			revisionSelected(selected);
		}
	};
	private final ISelectionChangedListener historyListListener = new ISelectionChangedListener() {
		public void selectionChanged(SelectionChangedEvent event) {
			ISelection selection= event.getSelection();
			if (selection instanceof IStructuredSelection) {
				Object first= ((IStructuredSelection) selection).getFirstElement();
				if (first != null)
					historyEntrySelected(first);
			}
		}
	};
	
	/**
	 * Open a text editor that supports the use of a revision ruler on the given
	 * file. If an appropriate editor is already open, it is returned. Otherwise
	 * a new editor is opened.
	 * 
	 * @param page
	 *            the page in which the editor is to be opened
	 * @param file
	 *            the file to be edited
	 * @return the open editor on the file
	 * @throws PartInitException
	 */
	public static AbstractDecoratedTextEditor openEditor(IWorkbenchPage page, IFile file) throws PartInitException {
		if (file == null)
			return null;
		IEditorPart[] openEditors = findOpenEditorsForFile(file);
		if (openEditors.length > 0) {
			AbstractDecoratedTextEditor te= findTextEditor(openEditors);
			if (te != null)
				return te;
		}
		
		// No existing editor references found, try to open a new editor for the file	
		try {
			IEditorDescriptor descrptr = IDE.getEditorDescriptor(file);
			// Try to open the associated editor only if its an internal editor
			// Also, if a non-text editor is already open, there is no need to try and open 
			// an editor since the open will find the non-text editor
			IEditorInput input = new FileEditorInput(file);
			if (descrptr.isInternal() && openEditors.length == 0){
				IEditorPart part = page.openEditor(input, IDE.getEditorDescriptor(file).getId(), true, IWorkbenchPage.MATCH_INPUT);
				if (part instanceof AbstractDecoratedTextEditor)
					return (AbstractDecoratedTextEditor)part;
				
				//editor opened is not a text editor - close it
				page.closeEditor(part, false);
			}
			//open file in default text editor	
			IEditorPart part = page.openEditor(input, IDEWorkbenchPlugin.DEFAULT_TEXT_EDITOR_ID, true, IWorkbenchPage.MATCH_ID);
			if (part != null && part instanceof AbstractDecoratedTextEditor)
				return (AbstractDecoratedTextEditor)part;
			
		} catch (PartInitException e) {
		}
	
        return null;
	}
	
	/**
	 * Open a text editor that supports the use of a revision ruler on the given
	 * file. If an appropriate editor is already open, it is returned. Otherwise
	 * a new editor is opened.
	 * 
	 * @param page
	 *            the page in which the editor is to be opened
	 * @param fileRevision
	 *            the file revision object
	 * @param storage
	 *            the storage that provides access to the contents of the file revision
	 * @return the open editor on the file revision
	 * @throws PartInitException
	 */
	public static AbstractDecoratedTextEditor openEditor(IWorkbenchPage page,
			Object fileRevision, IStorage storage) throws PartInitException {
		String id = getEditorId(storage);
		ITextEditor editor = getEditor(id, fileRevision, storage);
		if (editor instanceof AbstractDecoratedTextEditor)
			return (AbstractDecoratedTextEditor) editor;
		return null;
	}
	

    private static ITextEditor getEditor(String id, Object fileRevision, IStorage storage) throws PartInitException {
        final IWorkbench workbench= PlatformUI.getWorkbench();
        final IWorkbenchWindow window= workbench.getActiveWorkbenchWindow();
        IWorkbenchPage page= window.getActivePage();
		IEditorPart part = page.openEditor(new FileRevisionEditorInput(fileRevision, storage), id);
    	if (part instanceof ITextEditor) {
    		return (ITextEditor)part;
    	} else {
    		// We asked for a text editor but didn't get one
    		// so open a vanilla text editor
    		page.closeEditor(part, false);
    		part = page.openEditor(new FileRevisionEditorInput(fileRevision, storage), EditorsUI.DEFAULT_TEXT_EDITOR_ID);
    		if (part instanceof ITextEditor) {
    			return (ITextEditor)part;
    		} else {
    			// There is something really wrong so just bail
    			throw new PartInitException(TeamUIMessages.RevisionAnnotationController_0); 
    		}
    	}
    }

    private static String getEditorId(IStorage storage) {
        String id;
		IEditorRegistry registry = PlatformUI.getWorkbench().getEditorRegistry();
		IEditorDescriptor descriptor = registry.getDefaultEditor(storage.getName());
		if (descriptor == null || !descriptor.isInternal()) {
			id = EditorsUI.DEFAULT_TEXT_EDITOR_ID; 
		} else {
			try {
				if (isTextEditor(descriptor)) {
					id = descriptor.getId();
				} else {
					id = EditorsUI.DEFAULT_TEXT_EDITOR_ID;
				}
			} catch (CoreException e) {
				id = EditorsUI.DEFAULT_TEXT_EDITOR_ID;
			}
		}
        return id;
    }

	private static boolean isTextEditor(IEditorDescriptor descriptor)
			throws CoreException {
		if (descriptor instanceof EditorDescriptor) {
			EditorDescriptor desc = (EditorDescriptor) descriptor;
			return desc.createEditor() instanceof AbstractDecoratedTextEditor;
		}
		return false;
	}
	
	private static AbstractDecoratedTextEditor findOpenTextEditorForFile(IFile file) {
		if (file == null)
			return null;
        IEditorPart[] editors = findOpenEditorsForFile(file);
        return findTextEditor(editors);
	}
	
	private static AbstractDecoratedTextEditor findTextEditor(IEditorPart[] editors) {
		for (int i = 0; i < editors.length; i++) {
			IEditorPart editor = editors[i];
			if (editor instanceof AbstractDecoratedTextEditor)
				return (AbstractDecoratedTextEditor) editor;
		}
		return null;
	}
	
	private static IEditorPart[] findOpenEditorsForFile(IFile file) {
		if (file == null)
			return new IEditorPart[0];
        final IWorkbench workbench= PlatformUI.getWorkbench();
        final IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        final IEditorReference[] references= window.getActivePage().getEditorReferences();
        final List editors = new ArrayList();
		for (int i= 0; i < references.length; i++) {
			IEditorReference reference= references[i];
			try {
				if (file.equals(reference.getEditorInput().getAdapter(IFile.class))) {
					IEditorPart editor= reference.getEditor(false);
					editors.add(editor);
				}
			} catch (PartInitException e) {
				// ignore
			}
		}
		
        return (IEditorPart[]) editors.toArray(new IEditorPart[editors.size()]);
	}
	
	private static AbstractDecoratedTextEditor findOpenTextEditorFor(Object object) {
		if (object == null)
			return null;
		if (object instanceof IFile) {
			IFile file = (IFile) object;
			return findOpenTextEditorForFile(file);
		}
        final IWorkbench workbench= PlatformUI.getWorkbench();
        final IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
        IEditorReference[] references= window.getActivePage().getEditorReferences();
		for (int i= 0; i < references.length; i++) {
			IEditorReference reference= references[i];
			try {
				if (object.equals(reference.getEditorInput())) {
					IEditorPart editor= reference.getEditor(false);
					if (editor instanceof AbstractDecoratedTextEditor)
						return (AbstractDecoratedTextEditor) editor;
				}
			} catch (PartInitException e) {
				// ignore
			}
		}
		
        return null;
	}
	
	private static ISelectionProvider findEditorRevisonSelectionProvider(Object object) {
		ITextEditor editor= findOpenTextEditorFor(object);
		if (editor == null)
			return null;

		IRevisionRulerColumn column= (IRevisionRulerColumn) editor.getAdapter(IRevisionRulerColumn.class);
		if (column instanceof IRevisionRulerColumnExtension)
			return ((IRevisionRulerColumnExtension) column).getRevisionSelectionProvider();
		return null;
	}
	
	private RevisionAnnotationController(ISelectionProvider revisionRuler, ISelectionProvider historyList) {
		fHistoryListSelectionProvider = historyList;
		fRulerSelectionProvider= revisionRuler;
		if (fRulerSelectionProvider != null) {
			fRulerSelectionProvider.addSelectionChangedListener(rulerListener);
			fHistoryListSelectionProvider.addSelectionChangedListener(historyListListener);
		}
	}
	
	/**
	 * Create a controller that links an editor on a local file to a history list.
	 * @param file the local file
	 * @param historyList the history list selection provider
	 */
	public RevisionAnnotationController(IFile file, ISelectionProvider historyList) {
		this(findEditorRevisonSelectionProvider(file), historyList);
	}
	
	/**
	 * Create a controller that links an editor input on a remote file to a history list.
	 * @param editorInput the editor input for the remote file
	 * @param historyList the history list selection provider
	 */
	public RevisionAnnotationController(IStorageEditorInput editorInput,
			ISelectionProvider historyList) {
		this(findEditorRevisonSelectionProvider(editorInput), historyList);
	}

	/**
	 * Dispose of the controller. 
	 */
	public void dispose() {
		if (fRulerSelectionProvider != null) {
			fRulerSelectionProvider.removeSelectionChangedListener(rulerListener);
			fHistoryListSelectionProvider.removeSelectionChangedListener(historyListListener);
		}
	}

	/**
	 * Callback from the ruler when a particular revision has been selected by the user.
	 * By default, this method will set the selection of the history list selection 
	 * provider that was passed in the constructor using the history entry returned
	 * by {@link #getHistoryEntry(Revision)}. Subclasses may override.
	 * @param selected the selected revision
	 */
	protected void revisionSelected(Revision selected) {
		Object entry= getHistoryEntry(selected);
		
		if (entry != null) {
			IStructuredSelection selection = new StructuredSelection(entry);
			if (fHistoryListSelectionProvider instanceof Viewer) {
				Viewer v = (Viewer) fHistoryListSelectionProvider;
				v.setSelection(selection, true);
			} else {
				fHistoryListSelectionProvider.setSelection(selection);
			}
		}
	}
	
	/**
	 * Return the history list entry corresponding to the provided revision.
	 * THis method is called by the {@link #revisionSelected(Revision)} method in
	 * order to determine what the selection of the history list selection provider
	 * should be set to.
	 * @param selected the selected revision.
	 * @return the history list entry that corresponds to the provided revision.
	 */
	protected abstract Object getHistoryEntry(Revision selected);

	/**
	 * Callback that is invoked when the selection in the history list changes.
	 * @param historyEntry the history entry
	 */
	/* package */ void historyEntrySelected(Object historyEntry) {
		String id = getRevisionId(historyEntry);
		if (id != null && fRulerSelectionProvider != null) {
			fRulerSelectionProvider.setSelection(new StructuredSelection(id));
		}
	}
	
	/**
	 * Return the revision id associated with the given history list entry.
	 * This method is used to determine which revision in the revision ruler should
	 * be highlighted when the history list selection provider fires a selection changed event.
	 * By default, this method tries to adapt the entry to either {@link IFileRevision} or
	 * {@link IResourceVariant} in order to obtain the content identifier. Subclasses may override.
	 * 
	 * @param historyEntry the history list entry
	 * @return the id of the entry
	 */
	protected String getRevisionId(Object historyEntry) {
		IFileRevision revision= (IFileRevision)Utils.getAdapter(historyEntry, IFileRevision.class);
		if (revision != null) {
			return revision.getContentIdentifier();
		}
		IResourceVariant variant = (IResourceVariant)Utils.getAdapter(historyEntry, IResourceVariant.class);
		if (variant != null)
			return variant.getContentIdentifier();
		return null;
	}
	
}