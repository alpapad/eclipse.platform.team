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
package org.eclipse.team.internal.ccvs.ui.subscriber;

import java.text.DateFormat;
import java.util.*;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.subscribers.*;
import org.eclipse.team.core.subscribers.ChangeSet;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.core.synchronize.SyncInfo;
import org.eclipse.team.core.synchronize.SyncInfoSet;
import org.eclipse.team.core.variants.IResourceVariant;
import org.eclipse.team.internal.ccvs.core.*;
import org.eclipse.team.internal.ccvs.core.resources.*;
import org.eclipse.team.internal.ccvs.core.syncinfo.FolderSyncInfo;
import org.eclipse.team.internal.ccvs.core.syncinfo.ResourceSyncInfo;
import org.eclipse.team.internal.ccvs.core.util.Util;
import org.eclipse.team.internal.ccvs.ui.*;
import org.eclipse.team.internal.ccvs.ui.CVSUIPlugin;
import org.eclipse.team.internal.ccvs.ui.Policy;
import org.eclipse.team.internal.ccvs.ui.operations.RemoteLogOperation;
import org.eclipse.team.internal.ccvs.ui.operations.RemoteLogOperation.LogEntryCache;
import org.eclipse.team.internal.ui.Utils;
import org.eclipse.team.ui.synchronize.*;

/**
 * Collector that fetches the log for incoming CVS change sets
 */
public class CVSChangeSetCollector extends SyncInfoSetChangeSetCollector {

	// Log operation that is used to fetch revision histories from the server. It also
	// provides caching so we keep it around.
    private LogEntryCache logs;
	
	// Job that builds the layout in the background.
	private boolean shutdown = false;
	private FetchLogEntriesJob fetchLogEntriesJob;
	
	private DefaultCheckedInChangeSet defaultSet;
	
	/* *****************************************************************************
	 * Special sync info that has its kind already calculated.
	 */
	public class CVSUpdatableSyncInfo extends CVSSyncInfo {
		public int kind;
		public CVSUpdatableSyncInfo(int kind, IResource local, IResourceVariant base, IResourceVariant remote, Subscriber s) {
			super(local, base, remote, s);
			this.kind = kind;
		}

		protected int calculateKind() throws TeamException {
			return kind;
		}
	}
	
	/* *****************************************************************************
	 * Background job to fetch commit comments and update view
	 */
	private class FetchLogEntriesJob extends Job {
		private Set syncSets = new HashSet();
		public FetchLogEntriesJob() {
			super(Policy.bind("ChangeLogModelProvider.4"));  //$NON-NLS-1$
			setUser(false);
		}
		public boolean belongsTo(Object family) {
			return family == ISynchronizeManager.FAMILY_SYNCHRONIZE_OPERATION;
		}
		public IStatus run(IProgressMonitor monitor) {
			
				if (syncSets != null && !shutdown) {
					// Determine the sync sets for which to fetch comment nodes
					SyncInfoSet[] updates;
					synchronized (syncSets) {
						updates = (SyncInfoSet[]) syncSets.toArray(new SyncInfoSet[syncSets.size()]);
						syncSets.clear();
					}
					for (int i = 0; i < updates.length; i++) {
						calculateRoots(updates[i], monitor);
					}
				}
				return Status.OK_STATUS;
		
		}
		public void add(SyncInfoSet set) {
			synchronized(syncSets) {
				syncSets.add(set);
			}
			schedule();
		}
		public boolean shouldRun() {
			return !syncSets.isEmpty();
		}
	};
	
	private class DefaultCheckedInChangeSet extends CheckedInChangeSet {

	    private Date date = new Date();
	    
        public DefaultCheckedInChangeSet() {
            setName("[Unassigned]");
        }
        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.CheckedInChangeSet#getAuthor()
         */
        public String getAuthor() {
            return "";
        }

        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.CheckedInChangeSet#getDate()
         */
        public Date getDate() {
            return date;
        }

        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.ChangeSet#getComment()
         */
        public String getComment() {
            return "Unassigned";
        }
	    
	}
	
	private class CVSCheckedInChangeSet extends CheckedInChangeSet {

        private final ILogEntry entry;

        public CVSCheckedInChangeSet(ILogEntry entry) {
            this.entry = entry;
    		String date = DateFormat.getDateTimeInstance().format(entry.getDate());
    		String comment = HistoryView.flattenText(entry.getComment());
    		setName("["+entry.getAuthor()+ "] (" + date +") " + comment); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        
        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.CheckedInChangeSet#getAuthor()
         */
        public String getAuthor() {
            return entry.getAuthor();
        }

        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.CheckedInChangeSet#getDate()
         */
        public Date getDate() {
            return entry.getDate();
        }

        /* (non-Javadoc)
         * @see org.eclipse.team.core.subscribers.ChangeSet#getComment()
         */
        public String getComment() {
            return entry.getComment();
        }
	}
	
    public CVSChangeSetCollector(ISynchronizePageConfiguration configuration) {
        super(configuration);
    }

    /* (non-Javadoc)
     * @see org.eclipse.team.core.subscribers.SyncInfoSetChangeSetCollector#add(org.eclipse.team.core.synchronize.SyncInfo[])
     */
    protected void add(SyncInfo[] infos) {
        startUpdateJob(new SyncInfoSet(infos));
    }

    /* (non-Javadoc)
     * @see org.eclipse.team.ui.synchronize.SyncInfoSetChangeSetCollector#reset(org.eclipse.team.core.synchronize.SyncInfoSet)
     */
    public void reset(SyncInfoSet seedSet) {
        // Cancel any currently running job
        if (fetchLogEntriesJob != null) {
	        try {
	            fetchLogEntriesJob.cancel();
	            fetchLogEntriesJob.join();
	        } catch (InterruptedException e) {
	        }
        }
        super.reset(seedSet);
    }
    
	private synchronized void startUpdateJob(SyncInfoSet set) {
		if(fetchLogEntriesJob == null) {
			fetchLogEntriesJob = new FetchLogEntriesJob();
		}
		fetchLogEntriesJob.add(set);
	}
	
	private void calculateRoots(SyncInfoSet set, IProgressMonitor monitor) {
		try {
			monitor.beginTask(null, 100);
			// Decide which nodes we have to fetch log histories
			SyncInfo[] infos = set.getSyncInfos();
			ArrayList remoteChanges = new ArrayList();
			for (int i = 0; i < infos.length; i++) {
				SyncInfo info = infos[i];
				if(isRemoteChange(info)) {
					remoteChanges.add(info);
				}
			}	
			handleRemoteChanges((SyncInfo[]) remoteChanges.toArray(new SyncInfo[remoteChanges.size()]), monitor);
		} catch (CVSException e) {
			Utils.handle(e);
		} catch (InterruptedException e) {
		} finally {
			monitor.done();
		}
	}
	
	/*
	 * Return if this sync info should be considered as part of a remote change
	 * meaning that it can be placed inside an incoming commit set (i.e. the
	 * set is determined using the comments from the log entry of the file). 
	 */
	private boolean isRemoteChange(SyncInfo info) {
		int kind = info.getKind();
		if(info.getLocal().getType() != IResource.FILE) return false;
		if(info.getComparator().isThreeWay()) {
			return (kind & SyncInfo.DIRECTION_MASK) != SyncInfo.OUTGOING;
		}
		// For two-way, the change is only remote if it has a remote or has a base locally
		if (info.getRemote() != null) return true;
		ICVSFile file = CVSWorkspaceRoot.getCVSFileFor((IFile)info.getLocal());
		try {
            return file.getSyncBytes() != null;
        } catch (CVSException e) {
            // Log the error and exclude the file from consideration
            CVSUIPlugin.log(e);
            return false;
        }
	}
	
	/**
	 * Fetch the log histories for the remote changes and use this information
	 * to add each resource to an appropriate commit set.
     */
    private void handleRemoteChanges(final SyncInfo[] infos, final IProgressMonitor monitor) throws CVSException, InterruptedException {
        final LogEntryCache logs = getSyncInfoComment(infos, Policy.subMonitorFor(monitor, 80));
        runViewUpdate(new Runnable() {
            public void run() {
                addLogEntries(infos, logs, Policy.subMonitorFor(monitor, 10));
            }
        });
    }
    
	/**
	 * How do we tell which revision has the interesting log message? Use the later
	 * revision, since it probably has the most up-to-date comment.
	 */
	private LogEntryCache getSyncInfoComment(SyncInfo[] infos, IProgressMonitor monitor) throws CVSException, InterruptedException {
		if (logs == null) {
		    logs = new LogEntryCache();
		}
	    if (isTagComparison()) {
	        CVSTag tag = getCompareSubscriber().getTag();
            if (tag != null) {
	            // This is a comparison against a single tag
                // TODO: The local tags could be different per root or even mixed!!!
                fetchLogs(infos, logs, getLocalResourcesTag(infos), tag, monitor);
	        } else {
	            // Perform a fetch for each root in the subscriber
	            Map rootToInfosMap = getRootToInfosMap(infos);
	            monitor.beginTask(null, 100 * rootToInfosMap.size());
	            for (Iterator iter = rootToInfosMap.keySet().iterator(); iter.hasNext();) {
                    IResource root = (IResource) iter.next();
                    List infoList = ((List)rootToInfosMap.get(root));
                    SyncInfo[] infoArray = (SyncInfo[])infoList.toArray(new SyncInfo[infoList.size()]);
                    fetchLogs(infoArray, logs, getLocalResourcesTag(infoArray), getCompareSubscriber().getTag(root), Policy.subMonitorFor(monitor, 100));
                }
	            monitor.done();
	        }
	        
	    } else {
	        // Run the log command once with no tags
			fetchLogs(infos, logs, null, null, monitor);
	    }
		return logs;
	}
	
	private void fetchLogs(SyncInfo[] infos, LogEntryCache cache, CVSTag localTag, CVSTag remoteTag, IProgressMonitor monitor) throws CVSException, InterruptedException {
	    ICVSRemoteResource[] remoteResources = getRemotes(infos);
	    if (remoteResources.length > 0) {
			RemoteLogOperation logOperation = new RemoteLogOperation(getConfiguration().getSite().getPart(), remoteResources, localTag, remoteTag, cache);
			logOperation.execute(monitor);
	    }    
	}
	
	private ICVSRemoteResource[] getRemotes(SyncInfo[] infos) {
		List remotes = new ArrayList();
		for (int i = 0; i < infos.length; i++) {
			CVSSyncInfo info = (CVSSyncInfo)infos[i];
			if (info.getLocal().getType() != IResource.FILE) {
				continue;
			}	
			ICVSRemoteResource remote = getRemoteResource(info);
			if(remote != null) {
				remotes.add(remote);
			}
		}
		return (ICVSRemoteResource[]) remotes.toArray(new ICVSRemoteResource[remotes.size()]);
	}
	
    private boolean isTagComparison() {
        return getCompareSubscriber() != null;
    }
    
	/*
     * Return a map of IResource -> List of SyncInfo where the resource
     * is a root of the compare subscriber and the SyncInfo are children
     * of that root
     */
    private Map getRootToInfosMap(SyncInfo[] infos) {
        Map rootToInfosMap = new HashMap();
        IResource[] roots = getCompareSubscriber().roots();
        for (int i = 0; i < infos.length; i++) {
            SyncInfo info = infos[i];
            IPath localPath = info.getLocal().getFullPath();
            for (int j = 0; j < roots.length; j++) {
                IResource resource = roots[j];
                if (resource.getFullPath().isPrefixOf(localPath)) {
                    List infoList = (List)rootToInfosMap.get(resource);
                    if (infoList == null) {
                        infoList = new ArrayList();
                        rootToInfosMap.put(resource, infoList);
                    }
                    infoList.add(info);
                    break; // out of inner loop
                }
            }
            
        }
        return rootToInfosMap;
    }

    private CVSTag getLocalResourcesTag(SyncInfo[] infos) {
		try {
			for (int i = 0; i < infos.length; i++) {
				IResource local = infos[i].getLocal();
                ICVSResource cvsResource = CVSWorkspaceRoot.getCVSResourceFor(local);
				CVSTag tag = null;
				if(cvsResource.isFolder()) {
					FolderSyncInfo info = ((ICVSFolder)cvsResource).getFolderSyncInfo();
					if(info != null) {
						tag = info.getTag();									
					}
					if (tag != null && tag.getType() == CVSTag.BRANCH) {
						tag = Util.getAccurateFolderTag(local, tag);
					}
				} else {
					tag = Util.getAccurateFileTag(cvsResource);
				}
				if(tag == null) {
					tag = new CVSTag();
				}
				return tag;
			}
			return new CVSTag();
		} catch (CVSException e) {
			return new CVSTag();
		}
	}
	
    private CVSCompareSubscriber getCompareSubscriber() {
        ISynchronizeParticipant participant = getConfiguration().getParticipant();
        if (participant instanceof CompareParticipant) {
            return ((CompareParticipant)participant).getCVSCompareSubscriber();
        }
        return null;
    }

    private ICVSRemoteResource getRemoteResource(CVSSyncInfo info) {
		try {
			ICVSRemoteResource remote = (ICVSRemoteResource) info.getRemote();
			ICVSRemoteResource local = CVSWorkspaceRoot.getRemoteResourceFor(info.getLocal());
			if(local == null) {
				local = (ICVSRemoteResource)info.getBase();
			}
			
			boolean useRemote = true;
			if (local != null && remote != null) {
				String remoteRevision = getRevisionString(remote);
				String localRevision = getRevisionString(local);
				useRemote = useRemote(localRevision, remoteRevision);
			} else if (remote == null) {
				useRemote = false;
			}
			if (useRemote) {
				return remote;
			} else if (local != null) {
				return local;
			}
			return null;
		} catch (CVSException e) {
			CVSUIPlugin.log(e);
			return null;
		}
	}
	
    private boolean useRemote(String localRevision, String remoteRevision) {
        boolean useRemote;
        if (remoteRevision == null && localRevision == null) {
            useRemote = true;
        } else if (localRevision == null) {
            useRemote = true;
        } else if (remoteRevision == null) {
            useRemote = false;
        } else {
            useRemote = ResourceSyncInfo.isLaterRevision(remoteRevision, localRevision);
        }
        return useRemote;
    }

    private String getRevisionString(ICVSRemoteResource remoteFile) {
		if(remoteFile instanceof RemoteFile) {
			return ((RemoteFile)remoteFile).getRevision();
		}
		return null;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.team.ui.synchronize.views.HierarchicalModelProvider#dispose()
	 */
	public void dispose() {
		shutdown = true;
		if(fetchLogEntriesJob != null && fetchLogEntriesJob.getState() != Job.NONE) {
			fetchLogEntriesJob.cancel();
		}
		if (logs != null) {
		    logs.clearEntries();
		}
		super.dispose();
	}
	
    /*
	 * Add the following sync info elements to the viewer. It is assumed that these elements have associated
	 * log entries cached in the log operation.
	 */
	private void addLogEntries(SyncInfo[] commentInfos, LogEntryCache logs, IProgressMonitor monitor) {
		try {
			monitor.beginTask(null, commentInfos.length * 10);
			if (logs != null) {
				for (int i = 0; i < commentInfos.length; i++) {
					addSyncInfoToCommentNode(commentInfos[i], logs);
					monitor.worked(10);
				}
			}
		} finally {
			monitor.done();
		}
	}
	
	/*
	 * Create a node for the given sync info object. The logs should contain the log for this info.
	 * 
	 * @param info the info for which to create a node in the model
	 * @param log the cvs log for this node
	 */
	private void addSyncInfoToCommentNode(SyncInfo info, LogEntryCache logs) {
		ICVSRemoteResource remoteResource = getRemoteResource((CVSSyncInfo)info);
		if(isTagComparison() && remoteResource != null) {
			addMultipleRevisions(info, logs, remoteResource);
		} else {
			addSingleRevision(info, logs, remoteResource);
		}
	}
	
	/*
	 * Add a single log entry to the model.
	 * 
	 * @param info
	 * @param logs
	 * @param remoteResource
	 */
	private void addSingleRevision(SyncInfo info, LogEntryCache logs, ICVSRemoteResource remoteResource) {
		ILogEntry logEntry = logs.getLogEntry(remoteResource);
		// For incoming deletions grab the comment for the latest on the same branch
		// which is now in the attic.
		try {
			String remoteRevision = ((ICVSRemoteFile) remoteResource).getRevision();
			if (isDeletedRemotely(info)) {
				ILogEntry[] logEntries = logs.getLogEntries(remoteResource);
				for (int i = 0; i < logEntries.length; i++) {
					ILogEntry entry = logEntries[i];
					String revision = entry.getRevision();
					if (entry.isDeletion() && ResourceSyncInfo.isLaterRevision(revision, remoteRevision)) {
						logEntry = entry;
					}
				}
			}
		} catch (TeamException e) {
			// continue and skip deletion checks
		}
		addRemoteChange(info, remoteResource, logEntry);
	}
	
    /*
	 * Add multiple log entries to the model.
	 * 
	 * @param info
	 * @param logs
	 * @param remoteResource
	 */
	private void addMultipleRevisions(SyncInfo info, LogEntryCache logs, ICVSRemoteResource remoteResource) {
		ILogEntry[] logEntries = logs.getLogEntries(remoteResource);
		if(logEntries == null || logEntries.length == 0) {
			// If for some reason we don't have a log entry, try the latest
			// remote.
			addRemoteChange(info, null, null);
		} else {
			for (int i = 0; i < logEntries.length; i++) {
				ILogEntry entry = logEntries[i];
				addRemoteChange(info, remoteResource, entry);
			}
		}
	}
	
	private boolean isDeletedRemotely(SyncInfo info) {
		int kind = info.getKind();
		if(kind == (SyncInfo.INCOMING | SyncInfo.DELETION)) return true;
		if(SyncInfo.getDirection(kind) == SyncInfo.CONFLICTING && info.getRemote() == null) return true;
		return false;
	}
	
    /*
     * Add the remote change to an incoming commit set
     */
    private void addRemoteChange(SyncInfo info, ICVSRemoteResource remoteResource, ILogEntry logEntry) {
        if(remoteResource != null && logEntry != null && isRemoteChange(info)) {
	        ChangeSet set = getChangeSetFor(logEntry);
	        if (set == null) {
	            set = createChangeSetFor(logEntry);
	        	add(set);
	        }
	        if(requiresCustomSyncInfo(info, remoteResource, logEntry)) {
	        	info = new CVSUpdatableSyncInfo(info.getKind(), info.getLocal(), info.getBase(), (RemoteResource)logEntry.getRemoteFile(), ((CVSSyncInfo)info).getSubscriber());
	        	try {
	        		info.init();
	        	} catch (TeamException e) {
	        		// this shouldn't happen, we've provided our own calculate kind
	        	}
	        }
	        set.add(info);
        } else {
            // The info was not retrieved for the remote change for some reason.
            // Add the node to the root
            ChangeSet set = getDefaultChangeSet();
	        if (set == null) {
	            set = createDefaultChangeSet();
	        	add(set);
	        }
            set.add(info);
        }
    }
    
    private ChangeSet getDefaultChangeSet() {
        return defaultSet;
    }
    
    private ChangeSet createDefaultChangeSet() {
        return new DefaultCheckedInChangeSet();
    }

    private ChangeSet createChangeSetFor(ILogEntry logEntry) {
        return new CVSCheckedInChangeSet(logEntry);
    }

    private ChangeSet getChangeSetFor(ILogEntry logEntry) {
        ChangeSet[] sets = getSets();
        for (int i = 0; i < sets.length; i++) {
            ChangeSet set = sets[i];
            if (set.getComment().equals(logEntry.getComment())) {
                return set;
            }
        }
        return null;
    }

    private boolean requiresCustomSyncInfo(SyncInfo info, ICVSRemoteResource remoteResource, ILogEntry logEntry) {
		// Only interested in non-deletions
		if (logEntry.isDeletion() || !(info instanceof CVSSyncInfo)) return false;
		// Only require a custom sync info if the remote of the sync info
		// differs from the remote in the log entry
		IResourceVariant remote = info.getRemote();
		if (remote == null) return true;
		return !remote.equals(remoteResource);
	}
    
    /* (non-Javadoc)
     * @see org.eclipse.team.core.subscribers.ChangeSetCollector#remove(org.eclipse.team.core.subscribers.ChangeSet)
     */
    public void remove(ChangeSet set) {
        super.remove(set);
        if (set == defaultSet) {
            defaultSet = null;
        }
    }
}
