/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.team.ui.sync.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.internal.ui.dialogs.PreferencePageContainerDialog;
import org.eclipse.team.internal.ui.preferences.SyncViewerPreferencePage;

public class SyncViewerShowPreferencesAction extends Action {
	private final Shell shell;
	
	public SyncViewerShowPreferencesAction(Shell shell) {
		this.shell = shell;
		Utils.initAction(this, "action.syncViewPreferences."); //$NON-NLS-1$
	}

	public void run() {
		PreferencePage page = new SyncViewerPreferencePage();
		Dialog dialog = new PreferencePageContainerDialog(shell, page);
		dialog.setBlockOnOpen(true);
		dialog.open();
	}
}
