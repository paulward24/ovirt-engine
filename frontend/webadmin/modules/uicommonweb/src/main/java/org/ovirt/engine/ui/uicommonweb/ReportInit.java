package org.ovirt.engine.ui.uicommonweb;

import java.util.HashMap;
import java.util.Map;

import org.ovirt.engine.core.compat.Event;
import org.ovirt.engine.ui.frontend.AsyncQuery;
import org.ovirt.engine.ui.frontend.INewAsyncCallback;
import org.ovirt.engine.ui.uicommonweb.dataprovider.AsyncDataProvider;
import org.ovirt.engine.ui.uicompat.ReportParser;
import org.ovirt.engine.ui.uicompat.ReportParser.Dashboard;
import org.ovirt.engine.ui.uicompat.ReportParser.Resource;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.xml.client.impl.DOMParseException;

public class ReportInit {

    private static final ReportInit INSTANCE = new ReportInit();
    private boolean reportsEnabled = false;
    private boolean xmlInitialized = false;
    private boolean urlInitialized = false;
    private final Event reportsInitEvent = new Event("ReportsInitialize", ReportInit.class);
    private String reportBaseUrl = "";
    private boolean isCommunityEdition = false;

    private Map<String, Resource> resourceMap = new HashMap<String, Resource>();
    private Map<String, Dashboard> dashboardMap = new HashMap<String, Dashboard>();

    public static ReportInit getInstance() {
        return INSTANCE;
    }

    public boolean isCommunityEdition() {
        return isCommunityEdition;
    }

    public void init() {
        AsyncDataProvider.GetRedirectServletReportsPage(new AsyncQuery(this,
                new INewAsyncCallback() {
                    @Override
                    public void OnSuccess(Object target, Object returnValue) {
                        setReportBaseUrl((String) returnValue);
                    }
                }));

        parseReportsXML();
    }

    private ReportInit() {
    }

    public Event getReportsInitEvent() {
        return reportsInitEvent;
    }

    public Resource getResource(String type) {
        return resourceMap.get(type);
    }

    public Dashboard getDashboard(String type) {
        return dashboardMap.get(type);
    }

    private void parseReportsXML() {

        RequestBuilder requestBuilder =
                new RequestBuilder(RequestBuilder.GET, "Reports.xml");
        try {
            requestBuilder.sendRequest(null, new RequestCallback() {
                @Override
                public void onError(Request request, Throwable exception) {
                    setXmlInitialized();
                }

                @Override
                public void onResponseReceived(Request request, Response response) {
                    try {
                        ReportParser.getInstance().parseReport(response.getText());
                        resourceMap = ReportParser.getInstance().getResourceMap();
                        dashboardMap = ReportParser.getInstance().getDashboardMap();
                        isCommunityEdition = ReportParser.getInstance().isCommunityEdition();
                    } catch (DOMParseException e) {
                    } finally {
                        setXmlInitialized();
                    }

                }
            });
        } catch (RequestException e) {
            setXmlInitialized();
        }
    }

    public boolean isReportsEnabled() {
        return reportsEnabled;
    }

    private void setReportsEnabled(boolean reportsEnabled) {
        this.reportsEnabled = reportsEnabled;
    }

    public void setReportBaseUrl(String reportBaseUrl) {
        this.reportBaseUrl = reportBaseUrl;
        this.urlInitialized = true;
        checkIfInitFinished();
    }

    public String getReportBaseUrl() {
        return reportBaseUrl;
    }

    private void setXmlInitialized() {
        this.xmlInitialized = true;
        checkIfInitFinished();
    }

    private void checkIfInitFinished() {
        if (xmlInitialized && urlInitialized) {

            // Check if the reports should be enabled in this system
            if (!reportBaseUrl.equals("") && !resourceMap.isEmpty()) {
                setReportsEnabled(true);
            } else {
                setReportsEnabled(false);
            }

            // The initialization process blocks on this event after the login
            reportsInitEvent.raise(this, null);
        }
    }

}
