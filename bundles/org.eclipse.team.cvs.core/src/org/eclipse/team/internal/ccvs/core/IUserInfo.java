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
package org.eclipse.team.internal.ccvs.core;


/**
 * Instances of this class represent a username password pair.
 * Both values can be set and the username can be retrieved.
 * However, it is possible that the username is not mutable.
 * Users must check before trying to set the username.
 * 
 * Clients are not expected to implement this interface
 */
public interface IUserInfo {
	/**
	 * Get the username for this user.
	 */
	public String getUsername();
	/**
	 * Return true if the username is mutable. If not, setUsername should not be called.
	 */
	public boolean isUsernameMutable();
	/**
	 * Sets the password for this user.
	 */
	public void setPassword(String password);
	/**
	 * Sets the username for this user. This should not be called if
	 * isUsernameMutable() returns false.
	 */
	public void setUsername(String username);
}
