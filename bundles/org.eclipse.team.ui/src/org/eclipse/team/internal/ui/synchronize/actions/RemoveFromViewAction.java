/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize.actions;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.team.core.synchronize.SyncInfoSet;
import org.eclipse.team.internal.core.subscribers.WorkingSetFilteredSyncInfoCollector;
import org.eclipse.team.internal.ui.IPreferenceIds;
import org.eclipse.team.internal.ui.Policy;
import org.eclipse.team.internal.ui.TeamUIPlugin;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.synchronize.SubscriberParticipantPage;
import org.eclipse.team.ui.synchronize.ISynchronizePage;
import org.eclipse.team.ui.synchronize.ISynchronizePageConfiguration;
import org.eclipse.team.ui.synchronize.SynchronizeModelAction;
import org.eclipse.team.ui.synchronize.SynchronizeModelOperation;

/**
 * Remove the selected elemements from the page
 */
public class RemoveFromViewAction extends SynchronizeModelAction {

	protected RemoveFromViewAction(ISynchronizePageConfiguration configuration) {
		super(null, configuration);
		Utils.initAction(this, "action.removeFromView.", Policy.getBundle()); //$NON-NLS-1$
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.SynchronizeModelAction#run()
	 */
	public void run() {
		if (confirmRemove()) {
			super.run();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.SynchronizeModelAction#getSubscriberOperation(org.eclipse.team.ui.synchronize.ISynchronizePageConfiguration, org.eclipse.compare.structuremergeviewer.IDiffElement[])
	 */
	protected SynchronizeModelOperation getSubscriberOperation(ISynchronizePageConfiguration configuration, IDiffElement[] elements) {
		return new SynchronizeModelOperation(configuration, elements) {
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
				SyncInfoSet set = getSyncInfoSet();
				removeFromView(set);
			}
			protected boolean canRunAsJob() {
				return false;
			}
			/**
			 * Remove the sync info contained in the given set from the view.
			 * @param set the sync info set
			 */
			private void removeFromView(final SyncInfoSet set) {
				ISynchronizePage page = getConfiguration().getPage();
				if (page instanceof SubscriberParticipantPage) {
					final WorkingSetFilteredSyncInfoCollector collector = ((SubscriberParticipantPage)page).getCollector();
					collector.run(new IWorkspaceRunnable() {
						public void run(IProgressMonitor monitor) throws CoreException {
							collector.getWorkingSetSyncInfoSet().removeAll(set.getResources());
						}
					});
				}
			}
		};
	}

	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.SynchronizeModelAction#needsToSaveDirtyEditors()
	 */
	protected boolean needsToSaveDirtyEditors() {
		return false;
	}
	
	private boolean confirmRemove() {
		IPreferenceStore store = TeamUIPlugin.getPlugin().getPreferenceStore();
		if (store.getBoolean(IPreferenceIds.SYNCVIEW_REMOVE_FROM_VIEW_NO_PROMPT)) {
			return true;
		} else {
			MessageDialogWithToggle dialog = MessageDialogWithToggle.openOkCancelConfirm(
					getConfiguration().getSite().getShell(),
					"Confirm Remove",
					"The selected resources will be removed from the view. A resource will reappear if it is modified or if its synchronization state changes",
					"Don't show me this again",
					false,
					null,
					null);
			store.setValue(IPreferenceIds.SYNCVIEW_REMOVE_FROM_VIEW_NO_PROMPT, dialog.getToggleState());
			return dialog.getReturnCode() == Dialog.OK;
		}
	}
}
