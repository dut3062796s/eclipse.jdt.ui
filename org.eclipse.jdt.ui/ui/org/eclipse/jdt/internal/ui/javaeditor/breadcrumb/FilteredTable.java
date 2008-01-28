/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.javaeditor.breadcrumb;

import java.util.HashSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.events.TraverseListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.TableViewer;

import org.eclipse.ui.IWorkbenchPreferenceConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.WorkbenchJob;

import org.eclipse.jdt.internal.corext.util.Messages;

import org.eclipse.jdt.internal.ui.JavaPluginImages;

/**
 * A simple control that provides a text widget and a table viewer. The contents
 * of the text widget are used to drive a PatternFilter that is on the viewer.
 * 
 * @see PatternFilter
 * @see org.eclipse.ui.dialogs.FilteredTree
 * 
 * @since 3.4
 */
public class FilteredTable extends Composite {

	public static final class Direction {
		private Direction() {
		}
	}

	/**
	 * Walk the hierarchy up, from child to parent
	 */
	public static final Direction DIRECTION_UP= new Direction();
	
	/**
	 * Walk the hierarchy down, from parent to child 
	 */
	public static final Direction DIRECTION_DOWN= new Direction();

	public interface INavigateListener {
		void navigate(Direction direction);
	}

	/**
	 * The filter text widget to be used. This value may be
	 * <code>null</code> if there is no filter widget, or if the controls have
	 * not yet been created.
	 */
	private Text fFilterText;

	/**
	 * The control representing the clear button for the filter text entry. This
	 * value may be <code>null</code> if no such button exists, or if the
	 * controls have not yet been created.
	 */
	private ToolBarManager fFilterToolBar;

	/**
	 * The viewer for the filtered table. This value should never be
	 * <code>null</code> after the widget creation methods are complete.
	 */
	private TableViewer fTableViewer;

	/**
	 * The Composite on which the filter controls are created. This is used to
	 * set the background color of the filter controls to match the surrounding
	 * controls.
	 */
	private Composite fFilterComposite;

	/**
	 * The pattern filter for the table. This value must not be <code>null</code>.
	 */
	private PatternFilter fPatternFilter;

	/**
	 * The text to initially show in the filter text control.
	 */
	private String fInitialText= ""; //$NON-NLS-1$

	/**
	 * The job used to refresh the table.
	 */
	private Job fRefreshJob;

	/**
	 * Whether or not to show the filter controls (text and clear button). The
	 * default is to show these controls. This can be overridden by providing a
	 * setting in the product configuration file. The setting to add to not show
	 * these controls is:
	 * 
	 * org.eclipse.ui/SHOW_FILTERED_TEXTS=false
	 */
	private boolean fShowFilterControls;

	private Label fFilterSpacer;

	private final ListenerList fNavigateListeners;

	/**
	 * Create a new instance of the receiver.
	 * 
	 * @param parent
	 *            the parent <code>Composite</code>
	 * @param tableStyle
	 *            the style bits for the <code>Table</code>
	 * @param filter
	 *            the filter to be used
	 */
	public FilteredTable(Composite parent, int tableStyle, PatternFilter filter) {
		super(parent, SWT.NONE);

		fPatternFilter= filter;
		fShowFilterControls= PlatformUI.getPreferenceStore().getBoolean(IWorkbenchPreferenceConstants.SHOW_FILTERED_TEXTS);

		fNavigateListeners= new ListenerList();

		createControl(parent, tableStyle);
		createRefreshJob();

		setInitialText(BreadcrumbMessages.FilteredTable_initial_filter_text);
		setFont(parent.getFont());
	}

	/**
	 * Add a listener which will be informed about navigation
	 * 
	 * @param listener the listener to notify
	 */
	public void addNavigateListener(INavigateListener listener) {
		fNavigateListeners.add(listener);
	}

	/**
	 * Create the controls
	 * 
	 * @param parent
	 * @param tableStyle
	 */
	private void createControl(Composite parent, int tableStyle) {
		GridLayout layout= new GridLayout();
		layout.marginHeight= 0;
		layout.marginWidth= 0;
		layout.verticalSpacing= 0;
		setLayout(layout);
		setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		if (fShowFilterControls) {
			fFilterComposite= new Composite(this, SWT.NONE);
			GridLayout filterLayout= new GridLayout(3, false);
			filterLayout.marginHeight= 0;
			filterLayout.marginWidth= 0;
			fFilterComposite.setLayout(filterLayout);
			fFilterComposite.setFont(parent.getFont());

			createFilterControls(fFilterComposite);
			fFilterComposite.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		}

		Composite tableComposite= new Composite(this, SWT.NONE);
		GridLayout tableCompositeLayout= new GridLayout();
		tableCompositeLayout.marginHeight= 0;
		tableCompositeLayout.marginWidth= 0;
		tableComposite.setLayout(tableCompositeLayout);
		GridData data= new GridData(SWT.FILL, SWT.FILL, true, true);
		tableComposite.setLayoutData(data);
		createTableControl(tableComposite, tableStyle);
	}

	/**
	 * Create the filter controls. By default, a text and corresponding tool bar
	 * button that clears the contents of the text is created.
	 * 
	 * @param parent
	 *            parent <code>Composite</code> of the filter controls
	 * @return the <code>Composite</code> that contains the filter controls
	 */
	private Composite createFilterControls(Composite parent) {
		createFilterText(parent);
		createClearText(parent);
		if (fFilterToolBar != null) {
			fFilterToolBar.update(false);
			// initially there is no text to clear
			fFilterToolBar.getControl().setVisible(false);
		}
		return parent;
	}

	/**
	 * Creates and set up the table and table viewer. 
	 * 
	 * @param parent
	 *            parent <code>Composite</code>
	 * @param style
	 *            SWT style bits used to create the table
	 * @return the table
	 */
	private Control createTableControl(Composite parent, int style) {
		fTableViewer= new TableViewer(parent, style);
		GridData data= new GridData(SWT.FILL, SWT.FILL, true, true);
		fTableViewer.getControl().setLayoutData(data);
		fTableViewer.getControl().addDisposeListener(new DisposeListener() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.DisposeListener#widgetDisposed(org.eclipse.swt.events.DisposeEvent)
			 */
			public void widgetDisposed(DisposeEvent e) {
				fRefreshJob.cancel();
			}
		});
		fTableViewer.getControl().addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.ARROW_UP) {
					TableItem[] selection= fTableViewer.getTable().getSelection();
					if (selection.length == 1 && selection[0] == fTableViewer.getTable().getItem(0)) {
						fFilterText.setFocus();
						return;
					}
				} else if (e.keyCode == SWT.ARROW_RIGHT || e.keyCode == SWT.ARROW_LEFT) {
					Direction dir= e.keyCode == SWT.ARROW_LEFT ? DIRECTION_UP : DIRECTION_DOWN;
					
					Object[] listeners= fNavigateListeners.getListeners();
					for (int i= 0; i < listeners.length; i++) {
						((INavigateListener) listeners[i]).navigate(dir);
					}
				}

				e.doit= true;
				return;
			}
		});

		fTableViewer.addFilter(fPatternFilter);

		return fTableViewer.getControl();
	}

	/**
	 * Return the first item in the table that matches the filter pattern.
	 * 
	 * @param tableItems
	 * @return the first matching TableItem
	 */
	private TableItem getFirstMatchingItem(TableItem[] tableItems) {
		for (int i= 0; i < tableItems.length; i++) {
			if (fPatternFilter.select(fTableViewer, null, tableItems[i].getData())) {
				return tableItems[i];
			}
		}
		return null;
	}

	/**
	 * Create the refresh job for the receiver.
	 * 
	 */
	private void createRefreshJob() {
		fRefreshJob= new WorkbenchJob("Refresh Filter") {//$NON-NLS-1$
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
			 */
			public IStatus runInUIThread(IProgressMonitor monitor) {
				if (fTableViewer.getControl().isDisposed()) {
					return Status.CANCEL_STATUS;
				}

				String text= getFilterString();
				if (text == null) {
					return Status.OK_STATUS;
				}

				boolean initial= fInitialText != null && fInitialText.equals(text);
				if (initial) {
					fPatternFilter.setPattern(null);
				} else {
					fPatternFilter.setPattern(text);
				}

				try {
					fTableViewer.getTable().setRedraw(false);

					HashSet existing= new HashSet();
					TableItem[] items= fTableViewer.getTable().getItems();
					for (int i= 0; i < items.length; i++) {
						while (getDisplay().readAndDispatch()) {
							//allow to cancel this job if user types in filter field
							//see textChanged()
						}

						if (monitor.isCanceled())
							return Status.CANCEL_STATUS;

						if (!fPatternFilter.select(fTableViewer, null, items[i].getData())) {
							fTableViewer.remove(items[i].getData());
						} else {
							existing.add(items[i].getData());
						}
					}

					Object[] elements= ((IStructuredContentProvider) fTableViewer.getContentProvider()).getElements(fTableViewer.getInput());
					for (int i= 0; i < elements.length; i++) {
						while (getDisplay().readAndDispatch()) {
							//allow to cancel this job if user types in filter field
							//see textChanged()
						}

						if (monitor.isCanceled())
							return Status.CANCEL_STATUS;

						if (fPatternFilter.select(fTableViewer, null, elements[i]) && !existing.contains(elements[i])) {
							fTableViewer.add(elements[i]);
						}
					}
				} finally {
					fTableViewer.getTable().setRedraw(true);
				}

				updateToolbar(text.length() > 0 && !initial);
				return Status.OK_STATUS;
			}
		};
		fRefreshJob.setSystem(true);
	}

	private void updateToolbar(boolean visible) {
		if (fFilterToolBar != null) {
			fFilterToolBar.getControl().setVisible(visible);
		}
	}

	/**
	 * Creates the filter text and adds listeners. This method calls
	 * 
	 * @param parent
	 *            <code>Composite</code> of the filter text
	 */
	private void createFilterText(Composite parent) {
		fFilterSpacer= new Label(fFilterComposite, SWT.NONE);
		GridData layoutData= new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
		layoutData.widthHint= 14;
		fFilterSpacer.setLayoutData(layoutData);

		fFilterText= new Text(parent, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.CANCEL);

		fFilterText.getAccessible().addAccessibleListener(new AccessibleAdapter() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.accessibility.AccessibleListener#getName(org.eclipse.swt.accessibility.AccessibleEvent)
			 */
			public void getName(AccessibleEvent e) {
				String filterTextString= fFilterText.getText();
				if (filterTextString.length() == 0 || filterTextString.equals(fInitialText)) {
					e.result= fInitialText;
				} else {
					e.result= Messages.format(BreadcrumbMessages.FilteredTable_accessible_listener_text, new String[] { filterTextString, String.valueOf(getViewer().getTable().getItemCount()) });
				}
			}
		});

		fFilterText.addFocusListener(new FocusAdapter() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.FocusListener#focusLost(org.eclipse.swt.events.FocusEvent)
			 */
			public void focusGained(FocusEvent e) {
				/*
				 * Running in an asyncExec because the selectAll() does not
				 * appear to work when using mouse to give focus to text.
				 */
				Display display= fFilterText.getDisplay();
				display.asyncExec(new Runnable() {
					public void run() {
						if (!fFilterText.isDisposed()) {
							if (getInitialText().equals(fFilterText.getText().trim())) {
								fFilterText.selectAll();
							}
						}
					}
				});
			}
		});

		fFilterText.addKeyListener(new KeyAdapter() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.KeyAdapter#keyReleased(org.eclipse.swt.events.KeyEvent)
			 */
			public void keyPressed(KeyEvent e) {
				// on a CR we want to transfer focus to the list
				boolean hasItems= getViewer().getTable().getItemCount() > 0;
				if (hasItems && e.keyCode == SWT.ARROW_DOWN) {
					fTableViewer.getTable().setFocus();

					if (getViewer().getTable().getSelectionCount() == 0) {
						TableItem item= getViewer().getTable().getItem(0);
						getViewer().getTable().setSelection(item);
						ISelection sel= getViewer().getSelection();
						getViewer().setSelection(sel, true);
					}
				} else if (e.keyCode == SWT.ARROW_UP) {
					e.doit= false;
				} else if (e.character == SWT.CR) {
					return;
				}
			}
		});

		// enter key set focus to table
		fFilterText.addTraverseListener(new TraverseListener() {
			public void keyTraversed(TraverseEvent e) {
				if (e.detail == SWT.TRAVERSE_RETURN) {
					e.doit= false;
					if (getViewer().getTable().getItemCount() == 0) {
						Display.getCurrent().beep();
					} else {
						// if the initial filter text hasn't changed, do not try
						// to match
						boolean hasFocus= getViewer().getTable().setFocus();
						boolean textChanged= !getInitialText().equals(fFilterText.getText().trim());
						if (hasFocus && textChanged && fFilterText.getText().trim().length() > 0) {
							TableItem item= getFirstMatchingItem(getViewer().getTable().getItems());
							if (item != null) {
								getViewer().getTable().setSelection(item);
								ISelection sel= getViewer().getSelection();
								getViewer().setSelection(sel, true);
							}
						}
					}
				}
			}
		});

		fFilterText.addModifyListener(new ModifyListener() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.ModifyListener#modifyText(org.eclipse.swt.events.ModifyEvent)
			 */
			public void modifyText(ModifyEvent e) {
				textChanged();
			}
		});

		// if we're using a field with built in cancel we need to listen for
		// default selection changes (which tell us the cancel button has been
		// pressed)
		if ((fFilterText.getStyle() & SWT.CANCEL) != 0) {
			fFilterText.addSelectionListener(new SelectionAdapter() {
				/*
				 * (non-Javadoc)
				 * 
				 * @see org.eclipse.swt.events.SelectionAdapter#widgetDefaultSelected(org.eclipse.swt.events.SelectionEvent)
				 */
				public void widgetDefaultSelected(SelectionEvent e) {
					if (e.detail == SWT.CANCEL)
						clearText();
				}
			});
		}

		GridData gridData= new GridData(SWT.FILL, SWT.CENTER, true, false);
		// if the text widget supported cancel then it will have it's own
		// integrated button. We can take all of the space.
		if ((fFilterText.getStyle() & SWT.CANCEL) != 0)
			gridData.horizontalSpan= 2;
		fFilterText.setLayoutData(gridData);
	}

	/**
	 * Update the receiver after the text has changed.
	 */
	private void textChanged() {
		// cancel currently running job first, to prevent unnecessary redraw
		fRefreshJob.cancel();
		fRefreshJob.schedule(200);
	}

	/**
	 * Set the background for the widgets that support the filter text area.
	 * 
	 * @param background
	 *            background <code>Color</code> to set
	 */
	public void setBackground(Color background) {
		super.setBackground(background);
		if (fFilterComposite != null) {
			fFilterComposite.setBackground(background);
		}
		if (fFilterToolBar != null && fFilterToolBar.getControl() != null) {
			fFilterToolBar.getControl().setBackground(background);
		}
		if (fFilterSpacer != null) {
			fFilterSpacer.setBackground(background);
		}
	}

	/**
	 * Create the button that clears the text.
	 * 
	 * @param parent
	 *            parent <code>Composite</code> of toolbar button
	 */
	private void createClearText(Composite parent) {
		// only create the button if the text widget doesn't support one
		// natively
		if ((fFilterText.getStyle() & SWT.CANCEL) == 0) {
			fFilterToolBar= new ToolBarManager(SWT.FLAT | SWT.HORIZONTAL);
			fFilterToolBar.createControl(parent);

			IAction clearTextAction= new Action("", IAction.AS_PUSH_BUTTON) {//$NON-NLS-1$
				/*
				 * (non-Javadoc)
				 * 
				 * @see org.eclipse.jface.action.Action#run()
				 */
				public void run() {
					clearText();
				}
			};

			clearTextAction.setToolTipText(BreadcrumbMessages.FilteredTable_clear_button_tooltip);
			clearTextAction.setImageDescriptor(JavaPluginImages.DESC_ELCL_CLEAR);
			clearTextAction.setDisabledImageDescriptor(JavaPluginImages.DESC_DLCL_CLEAR);

			fFilterToolBar.add(clearTextAction);
		}
	}

	/**
	 * Clears the text in the filter text widget. Also removes the optional
	 * additional filter that is provided via addFilter(ViewerFilter).
	 */
	private void clearText() {
		setFilterText(""); //$NON-NLS-1$
		textChanged();
	}

	/**
	 * Set the text in the filter control.
	 * 
	 * @param string
	 */
	private void setFilterText(String string) {
		if (fFilterText != null) {
			fFilterText.setText(string);
			selectAll();
		}
	}

	/**
	 * Returns the pattern filter used by this table.
	 * 
	 * @return The pattern filter; never <code>null</code>.
	 */
	public final PatternFilter getPatternFilter() {
		return fPatternFilter;
	}

	/**
	 * Get the table viewer of the receiver.
	 * 
	 * @return the table viewer
	 */
	public TableViewer getViewer() {
		return fTableViewer;
	}

	/**
	 * Get the filter text for the receiver, if it was created. Otherwise return
	 * <code>null</code>.
	 * 
	 * @return the filter Text, or null if it was not created
	 */
	public Text getFilterControl() {
		return fFilterText;
	}

	/**
	 * Convenience method to return the text of the filter control. If the text
	 * widget is not created, then null is returned.
	 * 
	 * @return String in the text, or null if the text does not exist
	 */
	private String getFilterString() {
		return fFilterText != null ? fFilterText.getText() : null;
	}

	/**
	 * Set the text that will be shown until the first focus. A default value is
	 * provided, so this method only need be called if overriding the default
	 * initial text is desired.
	 * 
	 * @param text
	 *            initial text to appear in text field
	 */
	public void setInitialText(String text) {
		fInitialText= text;
		setFilterText(fInitialText);
		textChanged();
	}

	/**
	 * Select all text in the filter text field.
	 */
	private void selectAll() {
		if (fFilterText != null) {
			fFilterText.selectAll();
		}
	}

	/**
	 * Get the initial text for the receiver.
	 * 
	 * @return String
	 */
	private String getInitialText() {
		return fInitialText;
	}

}