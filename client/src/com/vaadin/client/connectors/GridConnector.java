/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.client.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.user.client.ui.Widget;
import com.vaadin.client.ComponentConnector;
import com.vaadin.client.ConnectorHierarchyChangeEvent;
import com.vaadin.client.MouseEventDetailsBuilder;
import com.vaadin.client.annotations.OnStateChange;
import com.vaadin.client.communication.StateChangeEvent;
import com.vaadin.client.connectors.RpcDataSourceConnector.RpcDataSource;
import com.vaadin.client.data.DataSource.RowHandle;
import com.vaadin.client.renderers.Renderer;
import com.vaadin.client.ui.AbstractFieldConnector;
import com.vaadin.client.ui.AbstractHasComponentsConnector;
import com.vaadin.client.ui.SimpleManagedLayout;
import com.vaadin.client.widget.grid.CellReference;
import com.vaadin.client.widget.grid.CellStyleGenerator;
import com.vaadin.client.widget.grid.EditorHandler;
import com.vaadin.client.widget.grid.RowReference;
import com.vaadin.client.widget.grid.RowStyleGenerator;
import com.vaadin.client.widget.grid.events.BodyClickHandler;
import com.vaadin.client.widget.grid.events.BodyDoubleClickHandler;
import com.vaadin.client.widget.grid.events.GridClickEvent;
import com.vaadin.client.widget.grid.events.GridDoubleClickEvent;
import com.vaadin.client.widget.grid.events.SelectAllEvent;
import com.vaadin.client.widget.grid.events.SelectAllHandler;
import com.vaadin.client.widget.grid.selection.AbstractRowHandleSelectionModel;
import com.vaadin.client.widget.grid.selection.SelectionEvent;
import com.vaadin.client.widget.grid.selection.SelectionHandler;
import com.vaadin.client.widget.grid.selection.SelectionModel;
import com.vaadin.client.widget.grid.selection.SelectionModelMulti;
import com.vaadin.client.widget.grid.selection.SelectionModelNone;
import com.vaadin.client.widget.grid.selection.SelectionModelSingle;
import com.vaadin.client.widget.grid.sort.SortEvent;
import com.vaadin.client.widget.grid.sort.SortHandler;
import com.vaadin.client.widget.grid.sort.SortOrder;
import com.vaadin.client.widgets.Grid;
import com.vaadin.client.widgets.Grid.Column;
import com.vaadin.client.widgets.Grid.FooterCell;
import com.vaadin.client.widgets.Grid.FooterRow;
import com.vaadin.client.widgets.Grid.HeaderCell;
import com.vaadin.client.widgets.Grid.HeaderRow;
import com.vaadin.shared.data.sort.SortDirection;
import com.vaadin.shared.ui.Connect;
import com.vaadin.shared.ui.grid.EditorClientRpc;
import com.vaadin.shared.ui.grid.EditorServerRpc;
import com.vaadin.shared.ui.grid.GridClientRpc;
import com.vaadin.shared.ui.grid.GridColumnState;
import com.vaadin.shared.ui.grid.GridConstants;
import com.vaadin.shared.ui.grid.GridServerRpc;
import com.vaadin.shared.ui.grid.GridState;
import com.vaadin.shared.ui.grid.GridState.SharedSelectionMode;
import com.vaadin.shared.ui.grid.GridStaticSectionState;
import com.vaadin.shared.ui.grid.GridStaticSectionState.CellState;
import com.vaadin.shared.ui.grid.GridStaticSectionState.RowState;
import com.vaadin.shared.ui.grid.ScrollDestination;

import elemental.json.JsonObject;
import elemental.json.JsonValue;

/**
 * Connects the client side {@link Grid} widget with the server side
 * {@link com.vaadin.ui.components.grid.Grid} component.
 * <p>
 * The Grid is typed to JSONObject. The structure of the JSONObject is described
 * at {@link com.vaadin.shared.data.DataProviderRpc#setRowData(int, List)
 * DataProviderRpc.setRowData(int, List)}.
 * 
 * @since 7.4
 * @author Vaadin Ltd
 */
@Connect(com.vaadin.ui.Grid.class)
public class GridConnector extends AbstractHasComponentsConnector implements
        SimpleManagedLayout {

    private static final class CustomCellStyleGenerator implements
            CellStyleGenerator<JsonObject> {
        @Override
        public String getStyle(CellReference<JsonObject> cellReference) {
            JsonObject row = cellReference.getRow();
            if (!row.hasKey(GridState.JSONKEY_CELLSTYLES)) {
                return null;
            }

            Column<?, JsonObject> column = cellReference.getColumn();
            if (!(column instanceof CustomGridColumn)) {
                // Selection checkbox column
                return null;
            }
            CustomGridColumn c = (CustomGridColumn) column;

            JsonObject cellStylesObject = row
                    .getObject(GridState.JSONKEY_CELLSTYLES);
            assert cellStylesObject != null;

            if (cellStylesObject.hasKey(c.id)) {
                return cellStylesObject.getString(c.id);
            } else {
                return null;
            }
        }

    }

    private static final class CustomRowStyleGenerator implements
            RowStyleGenerator<JsonObject> {
        @Override
        public String getStyle(RowReference<JsonObject> rowReference) {
            JsonObject row = rowReference.getRow();
            if (row.hasKey(GridState.JSONKEY_ROWSTYLE)) {
                return row.getString(GridState.JSONKEY_ROWSTYLE);
            } else {
                return null;
            }
        }

    }

    /**
     * Custom implementation of the custom grid column using a JSONObject to
     * represent the cell value and String as a column type.
     */
    private class CustomGridColumn extends Grid.Column<Object, JsonObject> {

        private final String id;

        private AbstractRendererConnector<Object> rendererConnector;

        private AbstractFieldConnector editorConnector;

        public CustomGridColumn(String id,
                AbstractRendererConnector<Object> rendererConnector) {
            super(rendererConnector.getRenderer());
            this.rendererConnector = rendererConnector;
            this.id = id;
        }

        @Override
        public Object getValue(final JsonObject obj) {
            final JsonObject rowData = obj.getObject(GridState.JSONKEY_DATA);

            if (rowData.hasKey(id)) {
                final JsonValue columnValue = rowData.get(id);

                return rendererConnector.decode(columnValue);
            }

            return null;
        }

        /*
         * Only used to check that the renderer connector will not change during
         * the column lifetime.
         * 
         * TODO remove once support for changing renderers is implemented
         */
        private AbstractRendererConnector<Object> getRendererConnector() {
            return rendererConnector;
        }

        private AbstractFieldConnector getEditorConnector() {
            return editorConnector;
        }

        private void setEditorConnector(AbstractFieldConnector editorConnector) {
            this.editorConnector = editorConnector;
        }
    }

    /*
     * An editor handler using Vaadin RPC to manage the editor state.
     */
    private class CustomEditorHandler implements EditorHandler<JsonObject> {

        private EditorServerRpc rpc = getRpcProxy(EditorServerRpc.class);

        private EditorRequest<JsonObject> currentRequest = null;
        private boolean serverInitiated = false;

        public CustomEditorHandler() {
            registerRpc(EditorClientRpc.class, new EditorClientRpc() {

                @Override
                public void bind(final int rowIndex) {
                    // call this finally to avoid issues with editing on init
                    Scheduler.get().scheduleFinally(new ScheduledCommand() {
                        @Override
                        public void execute() {
                            GridConnector.this.getWidget().editRow(rowIndex);
                        }
                    });
                }

                @Override
                public void cancel(int rowIndex) {
                    serverInitiated = true;
                    GridConnector.this.getWidget().cancelEditor();
                }

                @Override
                public void confirmBind(final boolean bindSucceeded) {
                    endRequest(bindSucceeded, null, null);
                }

                @Override
                public void confirmSave(boolean saveSucceeded,
                        String errorMessage, List<String> errorColumnsIds) {
                    endRequest(saveSucceeded, errorMessage, errorColumnsIds);
                }
            });
        }

        @Override
        public void bind(EditorRequest<JsonObject> request) {
            startRequest(request);
            rpc.bind(request.getRowIndex());
        }

        @Override
        public void save(EditorRequest<JsonObject> request) {
            startRequest(request);
            rpc.save(request.getRowIndex());
        }

        @Override
        public void cancel(EditorRequest<JsonObject> request) {
            if (!handleServerInitiated(request)) {
                // No startRequest as we don't get (or need)
                // a confirmation from the server
                rpc.cancel(request.getRowIndex());
            }
        }

        @Override
        public Widget getWidget(Grid.Column<?, JsonObject> column) {
            assert column != null;

            if (column instanceof CustomGridColumn) {
                AbstractFieldConnector c = ((CustomGridColumn) column)
                        .getEditorConnector();
                return c != null ? c.getWidget() : null;
            } else {
                throw new IllegalStateException("Unexpected column type: "
                        + column.getClass().getName());
            }
        }

        /**
         * Used to handle the case where the editor calls us because it was
         * invoked by the server via RPC and not by the client. In that case,
         * the request can be simply synchronously completed.
         * 
         * @param request
         *            the request object
         * @return true if the request was originally triggered by the server,
         *         false otherwise
         */
        private boolean handleServerInitiated(EditorRequest<?> request) {
            assert request != null : "Cannot handle null request";
            assert currentRequest == null : "Earlier request not yet finished";

            if (serverInitiated) {
                serverInitiated = false;
                request.success();
                return true;
            } else {
                return false;
            }
        }

        private void startRequest(EditorRequest<JsonObject> request) {
            assert currentRequest == null : "Earlier request not yet finished";

            currentRequest = request;
        }

        private void endRequest(boolean succeeded, String errorMessage,
                List<String> errorColumnsIds) {
            assert currentRequest != null : "Current request was null";
            /*
             * Clear current request first to ensure the state is valid if
             * another request is made in the callback.
             */
            EditorRequest<JsonObject> request = currentRequest;
            currentRequest = null;
            if (succeeded) {
                request.success();
            } else {
                Collection<Column<?, JsonObject>> errorColumns;
                if (errorColumnsIds != null) {
                    errorColumns = new ArrayList<Grid.Column<?, JsonObject>>();
                    for (String colId : errorColumnsIds) {
                        errorColumns.add(columnIdToColumn.get(colId));
                    }
                } else {
                    errorColumns = null;
                }

                request.failure(errorMessage, errorColumns);
            }
        }
    }

    private class ItemClickHandler implements BodyClickHandler,
            BodyDoubleClickHandler {

        @Override
        public void onClick(GridClickEvent event) {
            if (hasEventListener(GridConstants.ITEM_CLICK_EVENT_ID)) {
                fireItemClick(event.getTargetCell(), event.getNativeEvent());
            }
        }

        @Override
        public void onDoubleClick(GridDoubleClickEvent event) {
            if (hasEventListener(GridConstants.ITEM_CLICK_EVENT_ID)) {
                fireItemClick(event.getTargetCell(), event.getNativeEvent());
            }
        }

        private void fireItemClick(CellReference<?> cell, NativeEvent mouseEvent) {
            String rowKey = getRowKey((JsonObject) cell.getRow());
            String columnId = getColumnId(cell.getColumn());
            getRpcProxy(GridServerRpc.class)
                    .itemClick(
                            rowKey,
                            columnId,
                            MouseEventDetailsBuilder
                                    .buildMouseEventDetails(mouseEvent));
        }
    }

    /**
     * Maps a generated column id to a grid column instance
     */
    private Map<String, CustomGridColumn> columnIdToColumn = new HashMap<String, CustomGridColumn>();

    private AbstractRowHandleSelectionModel<JsonObject> selectionModel;
    private Set<String> selectedKeys = new LinkedHashSet<String>();
    private List<String> columnOrder = new ArrayList<String>();

    /**
     * updateFromState is set to true when {@link #updateSelectionFromState()}
     * makes changes to selection. This flag tells the
     * {@code internalSelectionChangeHandler} to not send same data straight
     * back to server. Said listener sets it back to false when handling that
     * event.
     */
    private boolean updatedFromState = false;

    private RpcDataSource dataSource;

    private SelectionHandler<JsonObject> internalSelectionChangeHandler = new SelectionHandler<JsonObject>() {
        @Override
        public void onSelect(SelectionEvent<JsonObject> event) {
            if (event.isBatchedSelection()) {
                return;
            }
            if (!updatedFromState) {
                for (JsonObject row : event.getRemoved()) {
                    selectedKeys.remove(dataSource.getRowKey(row));
                }

                for (JsonObject row : event.getAdded()) {
                    selectedKeys.add(dataSource.getRowKey(row));
                }

                getRpcProxy(GridServerRpc.class).select(
                        new ArrayList<String>(selectedKeys));
            } else {
                updatedFromState = false;
            }
        }
    };

    private ItemClickHandler itemClickHandler = new ItemClickHandler();

    private String lastKnownTheme = null;

    @Override
    @SuppressWarnings("unchecked")
    public Grid<JsonObject> getWidget() {
        return (Grid<JsonObject>) super.getWidget();
    }

    @Override
    public GridState getState() {
        return (GridState) super.getState();
    }

    @Override
    protected void init() {
        super.init();

        // All scroll RPC calls are executed finally to avoid issues on init
        registerRpc(GridClientRpc.class, new GridClientRpc() {
            @Override
            public void scrollToStart() {
                Scheduler.get().scheduleFinally(new ScheduledCommand() {
                    @Override
                    public void execute() {
                        getWidget().scrollToStart();
                    }
                });
            }

            @Override
            public void scrollToEnd() {
                Scheduler.get().scheduleFinally(new ScheduledCommand() {
                    @Override
                    public void execute() {
                        getWidget().scrollToEnd();
                    }
                });
            }

            @Override
            public void scrollToRow(final int row,
                    final ScrollDestination destination) {
                Scheduler.get().scheduleFinally(new ScheduledCommand() {
                    @Override
                    public void execute() {
                        getWidget().scrollToRow(row, destination);
                    }
                });
            }

            @Override
            public void recalculateColumnWidths() {
                getWidget().recalculateColumnWidths();
            }
        });

        getWidget().addSelectionHandler(internalSelectionChangeHandler);

        /* Item click events */
        getWidget().addBodyClickHandler(itemClickHandler);
        getWidget().addBodyDoubleClickHandler(itemClickHandler);

        getWidget().addSortHandler(new SortHandler<JsonObject>() {
            @Override
            public void sort(SortEvent<JsonObject> event) {
                List<SortOrder> order = event.getOrder();
                String[] columnIds = new String[order.size()];
                SortDirection[] directions = new SortDirection[order.size()];
                for (int i = 0; i < order.size(); i++) {
                    SortOrder sortOrder = order.get(i);
                    CustomGridColumn column = (CustomGridColumn) sortOrder
                            .getColumn();
                    columnIds[i] = column.id;

                    directions[i] = sortOrder.getDirection();
                }

                if (!Arrays.equals(columnIds, getState().sortColumns)
                        || !Arrays.equals(directions, getState().sortDirs)) {
                    // Report back to server if changed
                    getRpcProxy(GridServerRpc.class).sort(columnIds,
                            directions, event.isUserOriginated());
                }
            }
        });

        getWidget().addSelectAllHandler(new SelectAllHandler<JsonObject>() {

            @Override
            public void onSelectAll(SelectAllEvent<JsonObject> event) {
                getRpcProxy(GridServerRpc.class).selectAll();
            }

        });

        getWidget().setEditorHandler(new CustomEditorHandler());
        getLayoutManager().registerDependency(this, getWidget().getElement());
        layout();
    }

    @Override
    public void onStateChanged(final StateChangeEvent stateChangeEvent) {
        super.onStateChanged(stateChangeEvent);

        // Column updates
        if (stateChangeEvent.hasPropertyChanged("columns")) {

            // Remove old columns
            purgeRemovedColumns();

            // Add new columns
            for (GridColumnState state : getState().columns) {
                if (!columnIdToColumn.containsKey(state.id)) {
                    addColumnFromStateChangeEvent(state);
                }
                updateColumnFromState(columnIdToColumn.get(state.id), state);
            }
        }

        if (stateChangeEvent.hasPropertyChanged("columnOrder")) {
            if (orderNeedsUpdate(getState().columnOrder)) {
                updateColumnOrderFromState(getState().columnOrder);
            }
        }

        // Header and footer
        if (stateChangeEvent.hasPropertyChanged("header")) {
            updateHeaderFromState(getState().header);
        }

        if (stateChangeEvent.hasPropertyChanged("footer")) {
            updateFooterFromState(getState().footer);
        }

        // Selection
        if (stateChangeEvent.hasPropertyChanged("selectionMode")) {
            onSelectionModeChange();
            updateSelectDeselectAllowed();
        } else if (stateChangeEvent
                .hasPropertyChanged("singleSelectDeselectAllowed")) {
            updateSelectDeselectAllowed();
        }

        if (stateChangeEvent.hasPropertyChanged("selectedKeys")) {
            updateSelectionFromState();
        }

        // Sorting
        if (stateChangeEvent.hasPropertyChanged("sortColumns")
                || stateChangeEvent.hasPropertyChanged("sortDirs")) {
            onSortStateChange();
        }

        // Editor
        if (stateChangeEvent.hasPropertyChanged("editorEnabled")) {
            getWidget().setEditorEnabled(getState().editorEnabled);
        }

        // Frozen columns
        if (stateChangeEvent.hasPropertyChanged("frozenColumnCount")) {
            getWidget().setFrozenColumnCount(getState().frozenColumnCount);
        }

        // Theme features
        String activeTheme = getConnection().getUIConnector().getActiveTheme();
        if (lastKnownTheme == null) {
            lastKnownTheme = activeTheme;
        } else if (!lastKnownTheme.equals(activeTheme)) {
            getWidget().resetSizesFromDom();
            lastKnownTheme = activeTheme;
        }
    }

    private void updateSelectDeselectAllowed() {
        SelectionModel<JsonObject> model = getWidget().getSelectionModel();
        if (model instanceof SelectionModel.Single<?>) {
            ((SelectionModel.Single<?>) model)
                    .setDeselectAllowed(getState().singleSelectDeselectAllowed);
        }
    }

    private void updateColumnOrderFromState(List<String> stateColumnOrder) {
        CustomGridColumn[] columns = new CustomGridColumn[stateColumnOrder
                .size()];
        int i = 0;
        for (String id : stateColumnOrder) {
            columns[i] = columnIdToColumn.get(id);
            i++;
        }
        getWidget().setColumnOrder(columns);
        columnOrder = stateColumnOrder;
    }

    private boolean orderNeedsUpdate(List<String> stateColumnOrder) {
        if (stateColumnOrder.size() == columnOrder.size()) {
            for (int i = 0; i < columnOrder.size(); ++i) {
                if (!stateColumnOrder.get(i).equals(columnOrder.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void updateHeaderFromState(GridStaticSectionState state) {
        getWidget().setHeaderVisible(state.visible);

        while (getWidget().getHeaderRowCount() > 0) {
            getWidget().removeHeaderRow(0);
        }

        for (RowState rowState : state.rows) {
            HeaderRow row = getWidget().appendHeaderRow();

            for (CellState cellState : rowState.cells) {
                CustomGridColumn column = columnIdToColumn
                        .get(cellState.columnId);
                updateHeaderCellFromState(row.getCell(column), cellState);
            }

            for (Set<String> group : rowState.cellGroups.keySet()) {
                Grid.Column<?, ?>[] columns = new Grid.Column<?, ?>[group
                        .size()];
                CellState cellState = rowState.cellGroups.get(group);

                int i = 0;
                for (String columnId : group) {
                    columns[i] = columnIdToColumn.get(columnId);
                    i++;
                }

                // Set state to be the same as first in group.
                updateHeaderCellFromState(row.join(columns), cellState);
            }

            if (rowState.defaultRow) {
                getWidget().setDefaultHeaderRow(row);
            }

            row.setStyleName(rowState.styleName);
        }
    }

    private void updateHeaderCellFromState(HeaderCell cell, CellState cellState) {
        switch (cellState.type) {
        case TEXT:
            cell.setText(cellState.text);
            break;
        case HTML:
            cell.setHtml(cellState.html);
            break;
        case WIDGET:
            ComponentConnector connector = (ComponentConnector) cellState.connector;
            cell.setWidget(connector.getWidget());
            break;
        default:
            throw new IllegalStateException("unexpected cell type: "
                    + cellState.type);
        }
        cell.setStyleName(cellState.styleName);
    }

    private void updateFooterFromState(GridStaticSectionState state) {
        getWidget().setFooterVisible(state.visible);

        while (getWidget().getFooterRowCount() > 0) {
            getWidget().removeFooterRow(0);
        }

        for (RowState rowState : state.rows) {
            FooterRow row = getWidget().appendFooterRow();

            for (CellState cellState : rowState.cells) {
                CustomGridColumn column = columnIdToColumn
                        .get(cellState.columnId);
                updateFooterCellFromState(row.getCell(column), cellState);
            }

            for (Set<String> group : rowState.cellGroups.keySet()) {
                Grid.Column<?, ?>[] columns = new Grid.Column<?, ?>[group
                        .size()];
                CellState cellState = rowState.cellGroups.get(group);

                int i = 0;
                for (String columnId : group) {
                    columns[i] = columnIdToColumn.get(columnId);
                    i++;
                }

                // Set state to be the same as first in group.
                updateFooterCellFromState(row.join(columns), cellState);
            }

            row.setStyleName(rowState.styleName);
        }
    }

    private void updateFooterCellFromState(FooterCell cell, CellState cellState) {
        switch (cellState.type) {
        case TEXT:
            cell.setText(cellState.text);
            break;
        case HTML:
            cell.setHtml(cellState.html);
            break;
        case WIDGET:
            ComponentConnector connector = (ComponentConnector) cellState.connector;
            cell.setWidget(connector.getWidget());
            break;
        default:
            throw new IllegalStateException("unexpected cell type: "
                    + cellState.type);
        }
        cell.setStyleName(cellState.styleName);
    }

    /**
     * Updates a column from a state change event.
     * 
     * @param columnIndex
     *            The index of the column to update
     */
    private void updateColumnFromStateChangeEvent(GridColumnState columnState) {
        CustomGridColumn column = columnIdToColumn.get(columnState.id);

        updateColumnFromState(column, columnState);

        if (columnState.rendererConnector != column.getRendererConnector()) {
            throw new UnsupportedOperationException(
                    "Changing column renderer after initialization is currently unsupported");
        }
    }

    /**
     * Adds a new column to the grid widget from a state change event
     * 
     * @param columnIndex
     *            The index of the column, according to how it
     */
    private void addColumnFromStateChangeEvent(GridColumnState state) {
        @SuppressWarnings("unchecked")
        CustomGridColumn column = new CustomGridColumn(state.id,
                ((AbstractRendererConnector<Object>) state.rendererConnector));
        columnIdToColumn.put(state.id, column);

        /*
         * Add column to grid. Reordering is handled as a separate problem.
         */
        getWidget().addColumn(column);
        columnOrder.add(state.id);
    }

    /**
     * If we have a selection column renderer, we need to offset the index by
     * one when referring to the column index in the widget.
     */
    private int getWidgetColumnIndex(final int columnIndex) {
        Renderer<Boolean> selectionColumnRenderer = getWidget()
                .getSelectionModel().getSelectionColumnRenderer();
        int widgetColumnIndex = columnIndex;
        if (selectionColumnRenderer != null) {
            widgetColumnIndex++;
        }
        return widgetColumnIndex;
    }

    /**
     * Updates the column values from a state
     * 
     * @param column
     *            The column to update
     * @param state
     *            The state to get the data from
     */
    private static void updateColumnFromState(CustomGridColumn column,
            GridColumnState state) {
        column.setWidth(state.width);
        column.setMinimumWidth(state.minWidth);
        column.setMaximumWidth(state.maxWidth);
        column.setExpandRatio(state.expandRatio);

        column.setSortable(state.sortable);
        column.setEditable(state.editable);
        column.setEditorConnector((AbstractFieldConnector) state.editorConnector);
    }

    /**
     * Removes any orphan columns that has been removed from the state from the
     * grid
     */
    private void purgeRemovedColumns() {

        // Get columns still registered in the state
        Set<String> columnsInState = new HashSet<String>();
        for (GridColumnState columnState : getState().columns) {
            columnsInState.add(columnState.id);
        }

        // Remove column no longer in state
        Iterator<String> columnIdIterator = columnIdToColumn.keySet()
                .iterator();
        while (columnIdIterator.hasNext()) {
            String id = columnIdIterator.next();
            if (!columnsInState.contains(id)) {
                CustomGridColumn column = columnIdToColumn.get(id);
                columnIdIterator.remove();
                getWidget().removeColumn(column);
                columnOrder.remove(id);
            }
        }
    }

    public void setDataSource(RpcDataSource dataSource) {
        this.dataSource = dataSource;
        getWidget().setDataSource(this.dataSource);
    }

    private void onSelectionModeChange() {
        SharedSelectionMode mode = getState().selectionMode;
        if (mode == null) {
            getLogger().fine("ignored mode change");
            return;
        }

        AbstractRowHandleSelectionModel<JsonObject> model = createSelectionModel(mode);
        if (selectionModel == null
                || !model.getClass().equals(selectionModel.getClass())) {
            selectionModel = model;
            getWidget().setSelectionModel(model);
            selectedKeys.clear();
        }
    }

    @OnStateChange("hasCellStyleGenerator")
    private void onCellStyleGeneratorChange() {
        if (getState().hasCellStyleGenerator) {
            getWidget().setCellStyleGenerator(new CustomCellStyleGenerator());
        } else {
            getWidget().setCellStyleGenerator(null);
        }
    }

    @OnStateChange("hasRowStyleGenerator")
    private void onRowStyleGeneratorChange() {
        if (getState().hasRowStyleGenerator) {
            getWidget().setRowStyleGenerator(new CustomRowStyleGenerator());
        } else {
            getWidget().setRowStyleGenerator(null);
        }
    }

    private void updateSelectionFromState() {
        boolean changed = false;

        List<String> stateKeys = getState().selectedKeys;

        // find new deselections
        for (String key : selectedKeys) {
            if (!stateKeys.contains(key)) {
                changed = true;
                deselectByHandle(dataSource.getHandleByKey(key));
            }
        }

        // find new selections
        for (String key : stateKeys) {
            if (!selectedKeys.contains(key)) {
                changed = true;
                selectByHandle(dataSource.getHandleByKey(key));
            }
        }

        /*
         * A defensive copy in case the collection in the state is mutated
         * instead of re-assigned.
         */
        selectedKeys = new LinkedHashSet<String>(stateKeys);

        /*
         * We need to fire this event so that Grid is able to re-render the
         * selection changes (if applicable).
         */
        if (changed) {
            // At least for now there's no way to send the selected and/or
            // deselected row data. Some data is only stored as keys
            updatedFromState = true;
            getWidget().fireEvent(
                    new SelectionEvent<JsonObject>(getWidget(),
                            (List<JsonObject>) null, null, false));
        }
    }

    private void onSortStateChange() {
        List<SortOrder> sortOrder = new ArrayList<SortOrder>();

        String[] sortColumns = getState().sortColumns;
        SortDirection[] sortDirs = getState().sortDirs;

        for (int i = 0; i < sortColumns.length; i++) {
            sortOrder.add(new SortOrder(columnIdToColumn.get(sortColumns[i]),
                    sortDirs[i]));
        }

        getWidget().setSortOrder(sortOrder);
    }

    private Logger getLogger() {
        return Logger.getLogger(getClass().getName());
    }

    @SuppressWarnings("static-method")
    private AbstractRowHandleSelectionModel<JsonObject> createSelectionModel(
            SharedSelectionMode mode) {
        switch (mode) {
        case SINGLE:
            return new SelectionModelSingle<JsonObject>();
        case MULTI:
            return new SelectionModelMulti<JsonObject>();
        case NONE:
            return new SelectionModelNone<JsonObject>();
        default:
            throw new IllegalStateException("unexpected mode value: " + mode);
        }
    }

    /**
     * A workaround method for accessing the protected method
     * {@code AbstractRowHandleSelectionModel.selectByHandle}
     */
    private native void selectByHandle(RowHandle<JsonObject> handle)
    /*-{
        var model = this.@com.vaadin.client.connectors.GridConnector::selectionModel;
        model.@com.vaadin.client.widget.grid.selection.AbstractRowHandleSelectionModel::selectByHandle(*)(handle);
    }-*/;

    /**
     * A workaround method for accessing the protected method
     * {@code AbstractRowHandleSelectionModel.deselectByHandle}
     */
    private native void deselectByHandle(RowHandle<JsonObject> handle)
    /*-{
        var model = this.@com.vaadin.client.connectors.GridConnector::selectionModel;
        model.@com.vaadin.client.widget.grid.selection.AbstractRowHandleSelectionModel::deselectByHandle(*)(handle);
    }-*/;

    /**
     * Gets the row key for a row object.
     * 
     * @param row
     *            the row object
     * @return the key for the given row
     */
    public String getRowKey(JsonObject row) {
        final Object key = dataSource.getRowKey(row);
        assert key instanceof String : "Internal key was not a String but a "
                + key.getClass().getSimpleName() + " (" + key + ")";
        return (String) key;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.vaadin.client.HasComponentsConnector#updateCaption(com.vaadin.client
     * .ComponentConnector)
     */
    @Override
    public void updateCaption(ComponentConnector connector) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onConnectorHierarchyChange(
            ConnectorHierarchyChangeEvent connectorHierarchyChangeEvent) {
    }

    public String getColumnId(Grid.Column<?, ?> column) {
        if (column instanceof CustomGridColumn) {
            return ((CustomGridColumn) column).id;
        }
        return null;
    }

    @Override
    public void layout() {
        getWidget().onResize();
    }
}
