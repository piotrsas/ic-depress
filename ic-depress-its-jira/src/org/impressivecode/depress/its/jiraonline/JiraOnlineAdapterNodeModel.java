/*
 ImpressiveCode Depress Framework
 Copyright (C) 2013  ImpressiveCode contributors

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.impressivecode.depress.its.jiraonline;

import static com.google.common.collect.Lists.newArrayList;
import static org.impressivecode.depress.its.ITSAdapterTableFactory.createDataColumnSpec;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.impressivecode.depress.its.ITSAdapterTableFactory;
import org.impressivecode.depress.its.ITSAdapterTransformer;
import org.impressivecode.depress.its.ITSDataType;
import org.impressivecode.depress.its.ITSFilter;
import org.impressivecode.depress.its.jiraonline.JiraOnlineAdapterUriBuilder.Mode;
import org.impressivecode.depress.its.jiraonline.filter.CreationDateFilter;
import org.impressivecode.depress.its.jiraonline.filter.LastUpdateDateFilter;
import org.impressivecode.depress.its.jiraonline.filter.ProjectNameFilter;
import org.impressivecode.depress.its.jiraonline.filter.ResolvedDateFilter;
import org.impressivecode.depress.its.jiraonline.model.JiraOnlineIssueChangeRowItem;
import org.knime.core.data.DataTableSpec;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.DialogComponent;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelDate;
import org.knime.core.node.defaultnodesettings.SettingsModelInteger;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.knime.core.node.defaultnodesettings.SettingsModelStringArray;
import org.knime.core.node.port.PortObjectSpec;

import com.google.common.base.Preconditions;

/**
 * 
 * @author Marcin Kunert, Wroclaw University of Technology
 * @author Krzysztof Kwoka, Wroclaw University of Technology
 * @author Dawid Rutowicz, Wroclaw University of Technology
 * 
 */
public class JiraOnlineAdapterNodeModel extends NodeModel {

    private static final String DEFAULT_VALUE = "";
    private static final int INPUT_NODE_COUNT = 0;
    private static final int OUTPUT_NODE_COUNT = 2;
    private static final int DEFAULT_THREAD_COUNT = 50;
    private static final int STEPS_PER_TASK = 2;

    private static final String JIRA_URL = "depress.its.jiraonline.url";
    private static final String JIRA_LOGIN = "depress.its.jiraonline.login";
    private static final String JIRA_PASS = "depress.its.jiraonline.password";
    private static final String JIRA_START_DATE = "depress.its.jiraonline.startDate";
    private static final String JIRA_END_DATE = "depress.its.jiraonline.endDate";
    private static final String JIRA_JQL = "depress.its.jiraonline.jql";
    private static final String JIRA_STATUS = "depress.its.jiraonline.status";
    private static final String JIRA_HISTORY = "depress.its.jiraonline.history";
    private static final String THREAD_COUNT_SETTING = "depress.its.jiraonline.threadcount";
    private static final String FILTERS_SETTING = "depress.its.jiraonline.filters";

    private final SettingsModelString jiraSettingsURL = createSettingsURL();
    private final SettingsModelString jiraSettingsLogin = createSettingsLogin();
    private final SettingsModelString jiraSettingsPass = createSettingsPass();
    private final SettingsModelString jiraSettingsJQL = createSettingsJQL();
    private final SettingsModelBoolean jiraSettingsHistory = createSettingsHistory();
    private final SettingsModelInteger jiraSettingsThreadCount = createSettingsThreadCount();
    private final SettingsModelStringArray jiraSettingsFilter = createSettingsFilters();

    private static final NodeLogger LOGGER = NodeLogger.getLogger(JiraOnlineAdapterNodeModel.class);

    private JiraOnlineAdapterUriBuilder builder;
    private JiraOnlineAdapterRsClient client;

    private ExecutionContext exec;
    private ExecutionMonitor issueCountMonitor;
    private ExecutionMonitor issueListMonitor;
    private ExecutionMonitor issueHistoryMonitor;

    private ExecutorService executorService;

    private int issueTaskStepsSum;
    private int historyTaskStepsSum;
    private int issueTaskStepsCompleted;
    private int historyTaskStepsCompleted;

    private static List<ITSFilter> filters = createFilters();
    private static MapperManager mapperManager = new MapperManager();

    protected JiraOnlineAdapterNodeModel() {
        super(INPUT_NODE_COUNT, OUTPUT_NODE_COUNT);
    }

    @Override
    protected PortObjectSpec[] configure(final PortObjectSpec[] inSpecs) throws InvalidSettingsException {
        return new PortObjectSpec[OUTPUT_NODE_COUNT];
    }

    @Override
    protected BufferedDataTable[] execute(final BufferedDataTable[] inData, final ExecutionContext exec)
            throws Exception {
        long startTime = System.currentTimeMillis();
        this.exec = exec;
        prepareProgressMonitors();

        builder = prepareBuilder();
        client = new JiraOnlineAdapterRsClient();
        executorService = Executors.newFixedThreadPool(getThreadCount());

        List<URI> issueBatchLinks = prepareIssueBatchesLinks();

        issueListMonitor.setProgress(0);
        issueTaskStepsSum = issueBatchLinks.size() * STEPS_PER_TASK;

        mapperManager.refreshMaps();
        
        List<ITSDataType> issues = executeIssueTasks(issueBatchLinks);
        List<JiraOnlineIssueChangeRowItem> issuesHistory = executeHistoryTasks(issues);

        executorService.shutdown();

        BufferedDataTable out = transform(issues, exec);
        BufferedDataTable outHistory = transformHistory(issuesHistory, exec);

        long endTime = System.currentTimeMillis();
        LOGGER.warn("Finished in " + ((endTime - startTime) / 1000) + " seconds.");
        return new BufferedDataTable[] { out, outHistory };
    }

    private int getThreadCount() {
        return jiraSettingsThreadCount.getIntValue();
    }

    private List<URI> prepareIssueBatchesLinks() throws Exception {
        issueCountMonitor.setProgress(0);
        final int totalIssues = getIssuesCount();
        issueCountMonitor.setProgress(0.5);
        List<URI> issueBatchLinks = new ArrayList<>();

        while (totalIssues > builder.getNextStartingIndex()) {
            builder.prepareForNextBatch();
            issueBatchLinks.add(builder.build());
        }
        issueCountMonitor.setProgress(1);
        return issueBatchLinks;
    }

    private int getIssuesCount() throws Exception {
        String rawData = client.getJSON(builder.build());
        return JiraOnlineAdapterParser.getTotalIssuesCount(rawData);
    }

    private List<ITSDataType> executeIssueTasks(final List<URI> issueBatchLinks) throws InterruptedException,
    ExecutionException {
        List<Callable<List<ITSDataType>>> tasks = newArrayList();

        for (URI uri : issueBatchLinks) {
            tasks.add(new DownloadAndParseIssuesTask(uri));
        }
        List<Future<List<ITSDataType>>> partialResults = executorService.invokeAll(tasks);
        List<ITSDataType> issues = combinePartialIssueResults(partialResults);
        return issues;
    }

    private List<JiraOnlineIssueChangeRowItem> executeHistoryTasks(final List<ITSDataType> issues)
            throws InterruptedException, ExecutionException {
        List<JiraOnlineIssueChangeRowItem> issuesHistory;
        List<Callable<List<JiraOnlineIssueChangeRowItem>>> historyTasks = newArrayList();
        if (shouldDownloadHistory()) {
            historyTaskStepsSum = issues.size() * STEPS_PER_TASK;
            issueHistoryMonitor.setProgress(0);
            builder.setMode(Mode.SINGLE_ISSUE_WITH_HISTORY);
            for (ITSDataType issue : issues) {
                builder.setIssueKey(issue.getIssueId());
                historyTasks.add(new DownloadAndParseIssueHistoryTask(builder.build()));
            }
            List<Future<List<JiraOnlineIssueChangeRowItem>>> partialHistoryResults = executorService
                    .invokeAll(historyTasks);
            issuesHistory = combinePartialIssueHistoryResults(partialHistoryResults);
            builder.setMode(Mode.MULTIPLE_ISSUES);
        } else {
            issuesHistory = newArrayList();
        }
        return issuesHistory;
    }

    private void prepareProgressMonitors() {
        double issueListProgressPart = 0.9;
        double issueHistoryProgressPart = 0;

        if (shouldDownloadHistory()) {
            issueListProgressPart = 0.2;
            issueHistoryProgressPart = 0.7;
        }

        issueCountMonitor = exec.createSubProgress(0.1);

        issueListMonitor = exec.createSubProgress(issueListProgressPart);
        issueHistoryMonitor = exec.createSubProgress(issueHistoryProgressPart);

        issueTaskStepsCompleted = 0;
        historyTaskStepsCompleted = 0;

    }

    private List<ITSDataType> combinePartialIssueResults(final List<Future<List<ITSDataType>>> partialResults)
            throws InterruptedException, ExecutionException {
        List<ITSDataType> result = new ArrayList<>();

        for (Future<List<ITSDataType>> partialResult : partialResults) {
            result.addAll(partialResult.get());
        }

        return result;
    }

    private List<JiraOnlineIssueChangeRowItem> combinePartialIssueHistoryResults(
            final List<Future<List<JiraOnlineIssueChangeRowItem>>> partialResults) throws InterruptedException,
            ExecutionException {
        List<JiraOnlineIssueChangeRowItem> result = newArrayList();

        for (Future<List<JiraOnlineIssueChangeRowItem>> partialResult : partialResults) {
            result.addAll(partialResult.get());
        }

        return result;
    }

    private boolean shouldDownloadHistory() {
        return jiraSettingsHistory.getBooleanValue();
    }

    private void markProgressForIssue() {
        issueListMonitor.setProgress((double) ++issueTaskStepsCompleted / (double) issueTaskStepsSum);
    }

    private void markProgressForHistory() {
        issueHistoryMonitor.setProgress((double) ++historyTaskStepsCompleted / (double) historyTaskStepsSum);
    }

    private void checkForCancel() throws CanceledExecutionException {
        exec.checkCanceled();
    }

    private JiraOnlineAdapterUriBuilder prepareBuilder() {
        JiraOnlineAdapterUriBuilder builder = new JiraOnlineAdapterUriBuilder();

        builder.setHostname(jiraSettingsURL.getStringValue());
        if (jiraSettingsJQL.getStringValue() != null && !jiraSettingsJQL.getStringValue().equals("")) {
            builder.setJQL(jiraSettingsJQL.getStringValue());
        }

        builder.setFilters(getEnabledFilters());

        return builder;
    }

    private Collection<ITSFilter> getEnabledFilters() {
        ArrayList<ITSFilter> enabledFiters = new ArrayList<>();
        for (ITSFilter filter : getFilters()) {
            for (String enabledFilterName : jiraSettingsFilter.getStringArrayValue()) {
                if (enabledFilterName.equals(filter.getFilterModelId())) {
                    enabledFiters.add(filter);
                    break;
                }
            }
        }
        return enabledFiters;
    }

    private BufferedDataTable transform(final List<ITSDataType> entries, final ExecutionContext exec)
            throws CanceledExecutionException {
        ITSAdapterTransformer transformer = new ITSAdapterTransformer(ITSAdapterTableFactory.createDataColumnSpec());
        return transformer.transform(entries, exec);
    }

    private BufferedDataTable transformHistory(final List<JiraOnlineIssueChangeRowItem> entries,
            final ExecutionContext exec) throws CanceledExecutionException {
        JiraOnlineAdapterHistoryTransformer transformer = new JiraOnlineAdapterHistoryTransformer(
                JiraOnlineAdapterHistoryTableFactory.createDataColumnSpec());
        return transformer.transform(entries, exec);
    }

    @Override
    protected void reset() {
        // NOOP
    }

    @Override
    protected DataTableSpec[] configure(final DataTableSpec[] inSpecs) throws InvalidSettingsException {
        Preconditions.checkArgument(inSpecs.length == 0);
        return new DataTableSpec[] { createDataColumnSpec() };
    }

    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) {
        jiraSettingsURL.saveSettingsTo(settings);
        jiraSettingsLogin.saveSettingsTo(settings);
        jiraSettingsPass.saveSettingsTo(settings);
        jiraSettingsJQL.saveSettingsTo(settings);
        jiraSettingsHistory.saveSettingsTo(settings);
        jiraSettingsThreadCount.saveSettingsTo(settings);
        jiraSettingsFilter.saveSettingsTo(settings);

        for (ITSFilter filter : getFilters()) {
            for (DialogComponent component : filter.getDialogComponents()) {
                try {
                    component.getModel().saveSettingsTo(settings);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }

        for (DialogComponent component : mapperManager.getDialogComponents()) {
            try {
                component.getModel().saveSettingsTo(settings);
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }

    }

    @Override
    protected void loadValidatedSettingsFrom(final NodeSettingsRO settings) throws InvalidSettingsException {
        jiraSettingsURL.loadSettingsFrom(settings);
        jiraSettingsLogin.loadSettingsFrom(settings);
        jiraSettingsPass.loadSettingsFrom(settings);
        jiraSettingsJQL.loadSettingsFrom(settings);
        jiraSettingsHistory.loadSettingsFrom(settings);
        jiraSettingsThreadCount.loadSettingsFrom(settings);
        jiraSettingsFilter.loadSettingsFrom(settings);

        for (ITSFilter filter : getFilters()) {
            for (DialogComponent component : filter.getDialogComponents()) {
                try {
                    component.getModel().loadSettingsFrom(settings);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }

        for (DialogComponent component : mapperManager.getDialogComponents()) {
            try {
                component.getModel().loadSettingsFrom(settings);
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }
    }

    @Override
    protected void validateSettings(final NodeSettingsRO settings) throws InvalidSettingsException {
        jiraSettingsURL.validateSettings(settings);
        jiraSettingsLogin.validateSettings(settings);
        jiraSettingsPass.validateSettings(settings);
        jiraSettingsJQL.validateSettings(settings);
        jiraSettingsHistory.validateSettings(settings);
        jiraSettingsThreadCount.loadSettingsFrom(settings);
        jiraSettingsFilter.loadSettingsFrom(settings);

        for (ITSFilter filter : getFilters()) {
            for (DialogComponent component : filter.getDialogComponents()) {
                try {
                    component.getModel().validateSettings(settings);
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        }

        for (DialogComponent component : mapperManager.getDialogComponents()) {
            try {
                component.getModel().validateSettings(settings);
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }
    }

    @Override
    protected void loadInternals(final File internDir, final ExecutionMonitor exec) throws IOException,
    CanceledExecutionException {
        // NOOP
    }

    @Override
    protected void saveInternals(final File internDir, final ExecutionMonitor exec) throws IOException,
    CanceledExecutionException {
        // NOOP
    }

    static SettingsModelString createSettingsURL() {
        return new SettingsModelString(JIRA_URL, DEFAULT_VALUE);
    }

    static SettingsModelString createSettingsLogin() {
        return new SettingsModelString(JIRA_LOGIN, DEFAULT_VALUE);
    }

    static SettingsModelString createSettingsPass() {
        return new SettingsModelString(JIRA_PASS, DEFAULT_VALUE);
    }

    static SettingsModelDate createSettingsDateStart() {
        return new SettingsModelDate(JIRA_START_DATE);
    }

    static SettingsModelDate createSettingsDateEnd() {
        return new SettingsModelDate(JIRA_END_DATE);
    }

    static SettingsModelString createSettingsJQL() {
        return new SettingsModelString(JIRA_JQL, DEFAULT_VALUE);
    }

    static SettingsModelString createSettingsDateFilterStatusChooser() {
        return new SettingsModelString(JIRA_STATUS, DEFAULT_VALUE);
    }

    static SettingsModelBoolean createSettingsHistory() {
        return new SettingsModelBoolean(JIRA_HISTORY, false);
    }

    static SettingsModelInteger createSettingsThreadCount() {
        return new SettingsModelInteger(THREAD_COUNT_SETTING, DEFAULT_THREAD_COUNT);
    }

    static SettingsModelStringArray createSettingsFilters() {
        return new SettingsModelStringArray(FILTERS_SETTING, new String[] {});
    }

    private static List<ITSFilter> createFilters() {
        filters = new ArrayList<>();
        filters.add(new CreationDateFilter());
        filters.add(new ProjectNameFilter());
        filters.add(new LastUpdateDateFilter());
        filters.add(new ResolvedDateFilter());
        return filters;
    }

    public static MapperManager getMapperManager() {
        return mapperManager;
    }

    public static List<ITSFilter> getFilters() {
        return filters;
    }

    private class DownloadAndParseIssuesTask implements Callable<List<ITSDataType>> {

        private URI uri;

        public DownloadAndParseIssuesTask(final URI uri) {
            this.uri = uri;
        }

        @Override
        public List<ITSDataType> call() throws Exception {
            checkForCancel();

            String rawData = client.getJSON(uri);
            String hostname = builder.getHostname();

            markProgressForIssue();
            checkForCancel();

            List<ITSDataType> list = JiraOnlineAdapterParser.parseSingleIssueBatch(rawData, hostname);
            markProgressForIssue();

            return list;
        }
    }

    private class DownloadAndParseIssueHistoryTask implements Callable<List<JiraOnlineIssueChangeRowItem>> {

        private URI uri;

        public DownloadAndParseIssueHistoryTask(final URI uri) {
            this.uri = uri;
        }

        @Override
        public List<JiraOnlineIssueChangeRowItem> call() throws Exception {
            checkForCancel();

            String rawIssue = client.getJSON(uri);

            markProgressForHistory();
            checkForCancel();

            List<JiraOnlineIssueChangeRowItem> list = JiraOnlineAdapterParser.parseSingleIssue(rawIssue);
            markProgressForHistory();

            return list;
        }
    }

}
