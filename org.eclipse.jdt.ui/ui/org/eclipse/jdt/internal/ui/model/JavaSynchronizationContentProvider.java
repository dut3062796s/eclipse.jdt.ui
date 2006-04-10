/*******************************************************************************
 * Copyright (c) 2005, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.mapping.ResourceMapping;
import org.eclipse.core.resources.mapping.ResourceMappingContext;
import org.eclipse.core.resources.mapping.ResourceTraversal;

import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;

import org.eclipse.ui.navigator.IPipelinedTreeContentProvider;
import org.eclipse.ui.navigator.PipelinedShapeModification;
import org.eclipse.ui.navigator.PipelinedViewerUpdate;

import org.eclipse.team.core.diff.FastDiffFilter;
import org.eclipse.team.core.diff.IDiff;
import org.eclipse.team.core.diff.IDiffChangeEvent;
import org.eclipse.team.core.diff.IDiffTree;
import org.eclipse.team.core.diff.IDiffVisitor;
import org.eclipse.team.core.mapping.IResourceDiffTree;
import org.eclipse.team.core.mapping.ISynchronizationContext;
import org.eclipse.team.core.mapping.ISynchronizationScope;
import org.eclipse.team.core.mapping.provider.ResourceDiffTree;

import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptorProxy;
import org.eclipse.ltk.core.refactoring.history.RefactoringHistory;
import org.eclipse.ltk.ui.refactoring.model.AbstractSynchronizationContentProvider;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.util.JavaElementResourceMapping;

import org.eclipse.jdt.internal.ui.JavaPlugin;

/**
 * Java-aware synchronization content provider.
 * 
 * @since 3.2
 */
public final class JavaSynchronizationContentProvider extends AbstractSynchronizationContentProvider implements IPipelinedTreeContentProvider {

	/** The refactorings folder */
//	private static final String NAME_REFACTORING_FOLDER= ".refactorings"; //$NON-NLS-1$

	/**
	 * Returns the diffs associated with the element.
	 * 
	 * @param context
	 *            the synchronization context
	 * @param element
	 *            the element
	 * @return an array of diffs
	 */
	static IDiff[] getDiffs(final ISynchronizationContext context, final Object element) {
		return context.getDiffTree().getDiffs(getResourceTraversals(element));
	}

	/**
	 * Returns the resource mapping for the element.
	 * 
	 * @param element
	 *            the element to get the resource mapping
	 * @return the resource mapping
	 */
	static ResourceMapping getResourceMapping(final Object element) {
		if (element instanceof IJavaElement)
			return JavaElementResourceMapping.create((IJavaElement) element);
		if (element instanceof IAdaptable) {
			final IAdaptable adaptable= (IAdaptable) element;
			final Object adapted= adaptable.getAdapter(ResourceMapping.class);
			if (adapted instanceof ResourceMapping)
				return (ResourceMapping) adapted;
		}
		return null;
	}

	/**
	 * Returns the resource traversals for the element.
	 * 
	 * @param element
	 *            the element to get the resource traversals
	 * @return the resource traversals
	 */
	static ResourceTraversal[] getResourceTraversals(final Object element) {
		final ResourceMapping mapping= getResourceMapping(element);
		if (mapping != null)
			try {
				return mapping.getTraversals(ResourceMappingContext.LOCAL_CONTEXT, new NullProgressMonitor());
			} catch (final CoreException exception) {
				JavaPlugin.log(exception);
			}
		return new ResourceTraversal[0];
	}

	/** The content provider, or <code>null</code> */
	private ITreeContentProvider fContentProvider= null;

	/** The model root, or <code>null</code> */
	private Object fModelRoot= null;

	/**
	 * Returns the java element associated with the project.
	 * 
	 * @param project
	 *            the project
	 * @return the associated java element, or <code>null</code> if the
	 *         project is not a java project
	 */
	private IJavaProject asJavaProject(final IProject project) {
		try {
			if (project.getDescription().hasNature(JavaCore.NATURE_ID))
				return JavaCore.create(project);
		} catch (final CoreException exception) {
			// Only log the error for projects that are accessible (i.e. exist and are open)
			if (project.isAccessible())
				JavaPlugin.log(exception);
		}
		return null;
	}

	/**
	 * Converts the shape modification to use java elements.
	 * 
	 * @param modification
	 *            the shape modification to convert
	 */
	private void convertToJavaElements(final PipelinedShapeModification modification) {
		final Object parent= modification.getParent();
		if (parent instanceof IResource) {
			final IJavaElement project= asJavaProject(((IResource) parent).getProject());
			if (project != null) {
				modification.getChildren().clear();
				return;
			}
		}
		if (parent instanceof ISynchronizationContext) {
			final Set result= new HashSet();
			for (final Iterator iterator= modification.getChildren().iterator(); iterator.hasNext();) {
				final Object element= iterator.next();
				if (element instanceof IProject) {
					final IJavaElement project= asJavaProject((IProject) element);
					if (project != null) {
						iterator.remove();
						result.add(project);
					}
				}
			}
			modification.getChildren().addAll(result);
		}
	}

	/**
	 * Converts the viewer update to use java elements.
	 * 
	 * @param update
	 *            the viewer update to convert
	 * @return <code>true</code> if any elements have been converted,
	 *         <code>false</code> otherwise
	 */
	private boolean convertToJavaElements(final PipelinedViewerUpdate update) {
		final Set result= new HashSet();
		for (final Iterator iterator= update.getRefreshTargets().iterator(); iterator.hasNext();) {
			final Object element= iterator.next();
			if (element instanceof IProject) {
				final IJavaElement project= asJavaProject((IProject) element);
				if (project != null) {
					iterator.remove();
					result.add(project);
				}
			}
		}
		update.getRefreshTargets().addAll(result);
		return !result.isEmpty();
	}

	/**
	 * {@inheritDoc}
	 */
	public void diffsChanged(final IDiffChangeEvent event, final IProgressMonitor monitor) {
		syncExec(new Runnable() {

			public void run() {
				handleChange(event);
			}
		}, getViewer().getControl());
	}

	/**
	 * Returns all the existing projects that contain additions,
	 * removals or deletions.
	 * 
	 * @param event
	 *            the event
	 * @return the projects that contain changes
	 */
	private IJavaProject[] getChangedProjects(final IDiffChangeEvent event) {
		final Set result= new HashSet();
		final IDiff[] changes= event.getChanges();
		for (int index= 0; index < changes.length; index++) {
			final IResource resource= ResourceDiffTree.getResourceFor(changes[index]);
			if (resource != null) {
				final IJavaProject project= asJavaProject(resource.getProject());
				if (project != null)
					result.add(project);
			}
		}
		final IDiff[] additions= event.getAdditions();
		for (int index= 0; index < additions.length; index++) {
			final IResource resource= ResourceDiffTree.getResourceFor(additions[index]);
			if (resource != null) {
				final IJavaProject project= asJavaProject(resource.getProject());
				if (project != null)
					result.add(project);
			}
		}
		final IPath[] removals = event.getRemovals();
		for (int i = 0; i < removals.length; i++) {
			IPath path = removals[i];
			if (path.segmentCount() > 0) {
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segment(0));
				// Only consider projects that still exist
				if (project.exists()) {
					final IJavaProject javaProject= asJavaProject(project.getProject());
					if (javaProject != null)
						result.add(javaProject);
				}
			}
		}
		return (IJavaProject[]) result.toArray(new IJavaProject[result.size()]);
	}

	/**
	 * {@inheritDoc}
	 */
	protected Object[] getChildrenInContext(final ISynchronizationContext context, final Object parent, final Object[] children) {
		final Object[] elements= super.getChildrenInContext(context, parent, children);
		if (parent instanceof IPackageFragment)
			return getPackageFragmentChildren(context, parent, elements);
		else if (parent instanceof IPackageFragmentRoot)
			return getPackageFragmentRootChildren(context, parent, elements);
		else if (parent instanceof IJavaProject)
			return getJavaProjectChildren(context, parent, elements);
		else if (parent instanceof RefactoringHistory)
			return ((RefactoringHistory) parent).getDescriptors();
		return elements;
	}

	/**
	 * {@inheritDoc}
	 */
	protected ITreeContentProvider getDelegateContentProvider() {
		if (fContentProvider == null)
			fContentProvider= new JavaModelContentProvider();
		return fContentProvider;
	}

	/**
	 * Returns the projects that used to have changes in the diff tree 
	 * but have been deleted from the workspace.
	 * 
	 * @param event
	 *            the event
	 * @return the deleted projects
	 */
	private Set getDeletedProjects(final IDiffChangeEvent event) {
		final Set result= new HashSet();
		final IPath[] deletions= event.getRemovals();
		for (int index= 0; index < deletions.length; index++) {
			final IPath path= deletions[index];
			if (path.segmentCount() > 0) {
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segment(0));
				if (!project.isAccessible())
					result.add(project);
			}
		}
		return result;
	}

	/**
	 * Since the this content provider overrides the resource content provider,
	 * this method is only invoked when the resource content provider is
	 * disabled. In this case, we still want the Java projects to appear at the
	 * root of the view.
	 * 
	 * {@inheritDoc}
	 */
	public Object[] getElements(Object parent) {
		if (parent instanceof ISynchronizationContext)
			// Put the resource projects directly under the context
			parent= getModelRoot();
		return super.getElements(parent);
	}

	/**
	 * Returns the java project children in the current scope.
	 * 
	 * @param context
	 *            the synchronization context
	 * @param parent
	 *            the parent element
	 * @param children
	 *            the child elements
	 * @return the java project children
	 */
	private Object[] getJavaProjectChildren(final ISynchronizationContext context, final Object parent, final Object[] children) {
		final LinkedList list= new LinkedList();
		for (int index= 0; index < children.length; index++) {
			if (children[index] instanceof IPackageFragment) {
				final IPackageFragment fragment= (IPackageFragment) children[index];
				try {
					if (!fragment.hasChildren())
						continue;
				} catch (final JavaModelException exception) {
					JavaPlugin.log(exception);
				}
			}
			list.add(children[index]);
		}

		final IResource resource= JavaModelProvider.getResource(parent);
		if (resource != null) {
			final IResourceDiffTree tree= context.getDiffTree();
			final IResource[] members= tree.members(resource);
			for (int index= 0; index < members.length; index++)
				if (isVisible(context, members[index])) {
					if (!members[index].exists()) {
						// Add any phantom resources that are visible
						list.add(members[index]);
					}
//					if (members[index] instanceof IFolder) {
//						final IFolder folder= (IFolder) members[index];
//						if (folder.getName().equals(NAME_REFACTORING_FOLDER)) {
//							final RefactoringHistory history= getRefactorings(context, (IProject) resource, null);
//							if (!history.isEmpty()) {
//								list.remove(folder);
//								list.addFirst(history);
//							}
//						}
//					}
				}
		}
		return list.toArray(new Object[list.size()]);
	}

	/**
	 * {@inheritDoc}
	 */
	protected String getModelProviderId() {
		return JavaModelProvider.JAVA_MODEL_PROVIDER_ID;
	}

	/**
	 * {@inheritDoc}
	 */
	protected Object getModelRoot() {
		if (fModelRoot == null)
			fModelRoot= JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
		return fModelRoot;
	}

	/**
	 * Returns the package fragment children in the current scope.
	 * 
	 * @param context
	 *            the synchronization context
	 * @param parent
	 *            the parent element
	 * @param children
	 *            the child elements
	 * @return the package fragment children
	 */
	private Object[] getPackageFragmentChildren(final ISynchronizationContext context, final Object parent, final Object[] children) {
		final Set set= new HashSet();
		for (int index= 0; index < children.length; index++)
			set.add(children[index]);
		final IResource resource= ((IPackageFragment) parent).getResource();
		if (resource != null) {
			final IResourceDiffTree tree= context.getDiffTree();
			final IResource[] members= tree.members(resource);
			for (int index= 0; index < members.length; index++) {
				final int type= members[index].getType();
				if (type == IResource.FILE) {
					final IDiff diff= tree.getDiff(members[index]);
					if (diff != null && isVisible(diff))
						if (isInScope(context.getScope(), parent, members[index])) {
							final IJavaElement element= JavaCore.create(members[index]);
							if (element != null)
								set.add(element);
						}
				}
			}
		}
		return set.toArray(new Object[set.size()]);
	}

	/**
	 * Returns the package fragment root children in the current scope.
	 * 
	 * @param context
	 *            the synchronization context
	 * @param parent
	 *            the parent element
	 * @param children
	 *            the child elements
	 * @return the package fragment root children
	 */
	private Object[] getPackageFragmentRootChildren(final ISynchronizationContext context, final Object parent, final Object[] children) {
		final Set set= new HashSet();
		for (int index= 0; index < children.length; index++)
			set.add(children[index]);
		final IResource resource= JavaModelProvider.getResource(parent);
		if (resource != null) {
			final IResourceDiffTree tree= context.getDiffTree();
			final IResource[] members= tree.members(resource);
			for (int index= 0; index < members.length; index++) {
				final int type= members[index].getType();
				final boolean contained= isInScope(context.getScope(), parent, members[index]);
				final boolean visible= isVisible(context, members[index]);
				if (type == IResource.FILE && contained && visible) {
					final IJavaElement element= JavaCore.create((IFile) members[index]);
					if (element != null)
						set.add(element);
				} else if (type == IResource.FOLDER && contained && visible && tree.getDiff(members[index]) != null) {
					final IJavaElement element= JavaCore.create(members[index]);
					if (element != null)
						set.add(element);
				}
				if (type == IResource.FOLDER) {
					final IFolder folder= (IFolder) members[index];
					tree.accept(folder.getFullPath(), new IDiffVisitor() {

						public final boolean visit(final IDiff diff) {
							if (isVisible(diff)) {
								final IResource current= tree.getResource(diff);
								if (current != null) {
									final int kind= current.getType();
									if (kind == IResource.FILE) {
										final IJavaElement element= JavaCore.create(current.getParent());
										if (element != null)
											set.add(element);
									} else {
										final IJavaElement element= JavaCore.create(current);
										if (element != null)
											set.add(element);
									}
								}
							}
							return true;
						}
					}, IResource.DEPTH_INFINITE);
				}
			}
			return set.toArray(new Object[set.size()]);
		}
		return children;
	}

	/**
	 * {@inheritDoc}
	 */
	public void getPipelinedChildren(final Object parent, final Set children) {
		if (parent instanceof ISynchronizationContext) {
			// When a context is the root, the resource content provider returns
			// projects as direct children. We should replace any projects that
			// are Java projects with an IJavaProject
			final Set result= new HashSet(children.size());
			for (final Iterator iterator= children.iterator(); iterator.hasNext();) {
				final Object element= iterator.next();
				if (element instanceof IProject) {
					final IJavaElement java= asJavaProject((IProject) element);
					if (java != null) {
						iterator.remove();
						result.add(java);
					}
				}
			}
			children.addAll(result);
		} else if (parent instanceof ISynchronizationScope)
			// When the root is a scope, we should return the
			// Java model provider so all model providers appear
			// at the root of the viewer.
			children.add(getModelProvider());
	}

	/**
	 * {@inheritDoc}
	 */
	public void getPipelinedElements(final Object element, final Set elements) {
		getPipelinedChildren(element, elements);
	}

	/**
	 * {@inheritDoc}
	 */
	public Object getPipelinedParent(final Object element, final Object parent) {
		if (element instanceof IJavaElement)
			return getParent(element);
		return parent;
	}

	/**
	 * {@inheritDoc}
	 */
	protected ResourceTraversal[] getTraversals(final ISynchronizationContext context, final Object object) {
		return getResourceTraversals(object);
	}

	/**
	 * Returns the visible projects.
	 * 
	 * @return the visible projects
	 */
	private Set getVisibleProjects() {
		final TreeItem[] children= ((TreeViewer) getViewer()).getTree().getItems();
		final Set result= new HashSet();
		for (int index= 0; index < children.length; index++) {
			final Object data= children[index].getData();
			if (data instanceof IJavaProject)
				result.add(data);
		}
		return result;
	}

	/**
	 * Handles a diff change event.
	 * 
	 * @param event
	 *            the event
	 */
	private void handleChange(final IDiffChangeEvent event) {
		final Set existing= getVisibleProjects();
		// Get all existing and open projects that contain changes
		// and determine what needs to be done to the project
		// (i.e. add, remove or refresh)
		final IJavaProject[] changed= getChangedProjects(event);
		final List refreshes= new ArrayList(changed.length);
		final List additions= new ArrayList(changed.length);
		final List removals= new ArrayList(changed.length);
		for (int index= 0; index < changed.length; index++) {
			final IJavaProject project= changed[index];
			if (hasVisibleChanges(event.getTree(), project)) {
				if (existing.contains(project))
					refreshes.add(project);
				else
					additions.add(project);
			} else
				removals.add(project);
		}
		// Remove any java projects that correspond to deleted or closed projects
		final Set removed= getDeletedProjects(event);
		for (final Iterator iterator= existing.iterator(); iterator.hasNext();) {
			final IJavaProject element= (IJavaProject) iterator.next();
			if (removed.contains(element.getResource()))
				removals.add(element);
		}

		if (!removals.isEmpty() || !additions.isEmpty() || !refreshes.isEmpty()) {
			final TreeViewer viewer= (TreeViewer) getViewer();
			final Tree tree= viewer.getTree();
			try {
				tree.setRedraw(false);
				if (!additions.isEmpty())
					viewer.add(viewer.getInput(), additions.toArray());
				if (!removals.isEmpty())
					viewer.remove(viewer.getInput(), removals.toArray());
				if (!refreshes.isEmpty()) {
					for (final Iterator iter= refreshes.iterator(); iter.hasNext();)
						viewer.refresh(iter.next());
				}
			} finally {
				tree.setRedraw(true);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean hasChildren(final Object element) {
		if (element instanceof ICompilationUnit || element instanceof IFile || element instanceof RefactoringDescriptorProxy || element instanceof RefactoringDescriptor)
			return false;
		return super.hasChildren(element);
	}

	/**
	 * Returns whether the element has some children in the current scope.
	 * 
	 * @param scope
	 *            the synchronization scope
	 * @param element
	 *            the element
	 * @param resource
	 *            the resource
	 * @return <code>true</code> if it has some children, <code>false</code>
	 *         otherwise
	 */
	private boolean hasChildrenInScope(final ISynchronizationScope scope, final Object element, final IResource resource) {
		final IResource[] roots= scope.getRoots();
		final IPath path= resource.getFullPath();
		if (element instanceof IPackageFragment) {
			for (int index= 0; index < roots.length; index++)
				if (path.equals(roots[index].getFullPath().removeLastSegments(1)))
					return true;
			return false;
		}
		for (int index= 0; index < roots.length; index++)
			if (path.isPrefixOf(roots[index].getFullPath()))
				return true;
		return false;
	}

	/**
	 * Has the java project visible changes?
	 * 
	 * @param tree
	 *            the diff tree
	 * @param project
	 *            the java project
	 * @return <code>true</code> if it has visible changes, <code>false</code>
	 *         otherwise
	 */
	private boolean hasVisibleChanges(final IDiffTree tree, final IJavaProject project) {
		return tree.hasMatchingDiffs(project.getResource().getFullPath(), new FastDiffFilter() {

			public boolean select(final IDiff diff) {
				return isVisible(diff);
			}
		});
	}

	/**
	 * {@inheritDoc}
	 */
	public PipelinedShapeModification interceptAdd(final PipelinedShapeModification modification) {
		convertToJavaElements(modification);
		return modification;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean interceptRefresh(final PipelinedViewerUpdate update) {
		return convertToJavaElements(update);
	}

	/**
	 * {@inheritDoc}
	 */
	public PipelinedShapeModification interceptRemove(final PipelinedShapeModification modification) {
		convertToJavaElements(modification);
		return modification;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean interceptUpdate(final PipelinedViewerUpdate anUpdateSynchronization) {
		return convertToJavaElements(anUpdateSynchronization);
	}

	/**
	 * {@inheritDoc}
	 */
	protected boolean isInScope(final ISynchronizationScope scope, final Object parent, final Object element) {
		final IResource resource= JavaModelProvider.getResource(element);
		if (resource == null)
			return false;
		if (scope.contains(resource))
			return true;
		if (hasChildrenInScope(scope, element, resource))
			return true;
		return false;
	}

	/**
	 * Executes the given runnable.
	 * 
	 * @param runnable
	 *            the runnable
	 * @param control
	 *            the control
	 */
	private void syncExec(final Runnable runnable, final Control control) {
		if (control != null && !control.isDisposed())
			control.getDisplay().syncExec(new Runnable() {

				public void run() {
					if (!control.isDisposed())
						BusyIndicator.showWhile(control.getDisplay(), runnable);
				}
			});
	}
}
