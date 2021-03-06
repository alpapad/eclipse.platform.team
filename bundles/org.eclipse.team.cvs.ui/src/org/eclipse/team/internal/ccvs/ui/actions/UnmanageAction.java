/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ccvs.ui.actions;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.*;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.team.internal.ccvs.core.CVSException;
import org.eclipse.team.internal.ccvs.core.ICVSResource;
import org.eclipse.team.internal.ccvs.ui.CVSUIMessages;
import org.eclipse.team.internal.ccvs.ui.IHelpContextIds;
import org.eclipse.team.internal.ccvs.ui.operations.DisconnectOperation;
import org.eclipse.ui.PlatformUI;

/**
 * Unmanage action removes the cvs feature from a project and optionally
 * deletes the CVS meta information that is stored on disk.
 */
public class UnmanageAction extends WorkspaceAction {
	
	static class DeleteProjectDialog extends MessageDialog {

		boolean deleteContent = false;
		Button radio1;
		Button radio2;
		
		DeleteProjectDialog(Shell parentShell, IProject[] projects) {
			super(
				parentShell, 
				getTitle(projects), 
				null,	// accept the default window icon
				getMessage(projects),
				MessageDialog.QUESTION, 
				new String[] {IDialogConstants.YES_LABEL, IDialogConstants.NO_LABEL},
				0); 	// yes is the default
		}
		
		static String getTitle(IProject[] projects) {
			if (projects.length == 1)
				return CVSUIMessages.Unmanage_title;  
			else
				return CVSUIMessages.Unmanage_titleN;  
		}
		
		static String getMessage(IProject[] projects) {
			if (projects.length == 1) {
				IProject project = projects[0];
				return NLS.bind(CVSUIMessages.Unmanage_message, new String[] { project.getName() });  
			}
			else {
				return NLS.bind(CVSUIMessages.Unmanage_messageN, new String[] { new Integer(projects.length).toString() });  
			}
		}
		
		protected Control createCustomArea(Composite parent) {
			Composite composite = new Composite(parent, SWT.NONE);
			composite.setLayout(new GridLayout());
			radio1 = new Button(composite, SWT.RADIO);
			radio1.addSelectionListener(selectionListener);
			
			radio1.setText(CVSUIMessages.Unmanage_option2); 

			radio2 = new Button(composite, SWT.RADIO);
			radio2.addSelectionListener(selectionListener);

			radio2.setText(CVSUIMessages.Unmanage_option1); 
			
			// set initial state
			radio1.setSelection(deleteContent);
			radio2.setSelection(!deleteContent);
			
            PlatformUI.getWorkbench().getHelpSystem().setHelp(composite, IHelpContextIds.DISCONNECT_ACTION);
			
			return composite;
		}
		
		private SelectionListener selectionListener = new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				Button button = (Button) e.widget;
				if (button.getSelection()) {
					deleteContent = (button == radio1);
				}
			}
		};
		
		public boolean getDeleteContent() {
			return deleteContent;
		}
	}
	
	private boolean deleteContent = false;
	
	/*
	 * @see IActionDelegate#run(IAction)
	 */
	public void execute(IAction action) throws InterruptedException, InvocationTargetException {
		if(confirmDeleteProjects()) {
			new DisconnectOperation(getTargetPart(), getSelectedProjects(), deleteContent)
				.run();
		}
	}

	boolean confirmDeleteProjects() {
		final int[] result = new int[] { Window.OK };
		IProject[] projects = getSelectedProjects();
		final DeleteProjectDialog dialog = new DeleteProjectDialog(getShell(), projects);
		getShell().getDisplay().syncExec(new Runnable() {
			public void run() {
				result[0] = dialog.open();
			}
		});		
		deleteContent = dialog.getDeleteContent();
		return result[0] == 0;  // YES
	}
	
	/**
	 * @see org.eclipse.team.internal.ccvs.ui.actions.CVSAction#getErrorTitle()
	 */
	protected String getErrorTitle() {
		return CVSUIMessages.Unmanage_unmanagingError;
	}

	/**
	 * @see org.eclipse.team.internal.ccvs.ui.actions.WorkspaceAction#isEnabledForCVSResource(org.eclipse.team.internal.ccvs.core.ICVSResource)
	 */
	protected boolean isEnabledForCVSResource(ICVSResource cvsResource) throws CVSException {
		IResource resource = cvsResource.getIResource();
		return resource != null && resource.getType() == IResource.PROJECT;
	}

}
