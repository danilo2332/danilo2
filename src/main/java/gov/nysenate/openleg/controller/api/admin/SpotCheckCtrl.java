package gov.nysenate.openleg.controller.api.admin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import gov.nysenate.openleg.client.response.base.BaseResponse;
import gov.nysenate.openleg.client.response.base.ListViewResponse;
import gov.nysenate.openleg.client.response.base.SimpleResponse;
import gov.nysenate.openleg.client.response.base.ViewObjectResponse;
import gov.nysenate.openleg.client.view.base.ListView;
import gov.nysenate.openleg.client.view.spotcheck.MismatchSummaryView;
import gov.nysenate.openleg.client.view.spotcheck.MismatchView;
import gov.nysenate.openleg.controller.api.base.BaseCtrl;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.OrderBy;
import gov.nysenate.openleg.dao.base.PaginatedList;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.spotcheck.MismatchOrderBy;
import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.spotcheck.*;
import gov.nysenate.openleg.service.spotcheck.base.SpotCheckReportService;
import gov.nysenate.openleg.service.spotcheck.base.SpotcheckRunService;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static gov.nysenate.openleg.controller.api.base.BaseCtrl.BASE_ADMIN_API_PATH;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequestMapping(value = BASE_ADMIN_API_PATH + "/spotcheck", produces = APPLICATION_JSON_VALUE)
public class SpotCheckCtrl extends BaseCtrl
{
    private static final Logger logger = LoggerFactory.getLogger(SpotCheckCtrl.class);

    @Autowired private List<SpotCheckReportService<?>> reportServices;
    @Autowired private SpotcheckRunService spotcheckRunService;

    private ImmutableMap<SpotCheckRefType, SpotCheckReportService<?>> reportServiceMap;

    @PostConstruct
    public void init() {
        reportServiceMap = ImmutableMap.copyOf(
                reportServices.stream()
                        .collect(Collectors.toMap(SpotCheckReportService::getSpotcheckRefType, Function.identity(),(a, b) -> b)));
    }

    /**
     * Spotcheck Mismatch API
     *
     * <p>Queries for spotcheck mismatches matching the supplied parameters.
     *
     * <p>Usage: (GET) /api/3/admin/spotcheck/mismatches
     *
     * <p>Request Parameters: <ul>
     *                     <li>datasource - string - retrieves mismatches for the specified datasource.
     *                     <li>contentType - string - retrieves mismatches for the specified content type.
     *                     <li>mismatchStatuses - string[] - optional, default [NEW, EXISTING, REGRESSION]
     *                                      - retrieves mismatches with any of the given mismatch status.
     *                     <li>ignoredStatuses - string[] - optional, default [NOT_IGNORED] - retrieves mismatches with the given ignore status.
     *                     <li>fromDate - string (ISO date) - optional, default start of the session encompassing toDate,
     *                              - retrieves mismatches after this date.
     *                     <li>toDate - string (ISO date) - optional, default is now - retrieve mismatches as of this date.
     *                     <li>orderBy - string - optional, order results by the specified field, must be a valid {@link MismatchOrderBy} value.
     *                              - Defaults to REFERENCE_DATE.
     *                     <li>sort - string - optional, a SortOrder value representing the sort order. Defaults to DESC
     *                     <li>limit - int - limit the number of results.
     *                     <li>offset - int - start results from an offset.
     *                     </ul>
     */
    @RequiresPermissions("admin:view")
    @RequestMapping(value = "/mismatches", method = RequestMethod.GET)
    public BaseResponse getMismatches(@RequestParam String datasource,
                                      @RequestParam String contentType,
                                      @RequestParam(required = false) String[] mismatchStatuses,
                                      @RequestParam(required = false) String[] ignoredStatuses,
                                      @RequestParam(required = false) String fromDate,
                                      @RequestParam(required = false) String toDate,
                                      @RequestParam(required = false) String orderBy,
                                      @RequestParam(required = false) String sort,
                                      WebRequest request) {
        SpotCheckDataSource ds = getEnumParameter("datasource", datasource, SpotCheckDataSource.class);
        SpotCheckContentType ct = getEnumParameter("contentType", contentType, SpotCheckContentType.class);
        Set<SpotCheckMismatchStatus> ms = mismatchStatuses == null
                ? EnumSet.of(SpotCheckMismatchStatus.NEW, SpotCheckMismatchStatus.EXISTING, SpotCheckMismatchStatus.REGRESSION)
                : Lists.newArrayList(mismatchStatuses).stream().map(s -> getEnumParameter("mismatchStatuses", s, SpotCheckMismatchStatus.class)).collect(Collectors.toSet());
        Set<SpotCheckMismatchIgnore> igs = ignoredStatuses == null
                ? EnumSet.of(SpotCheckMismatchIgnore.NOT_IGNORED)
                : Lists.newArrayList(ignoredStatuses).stream().map(i -> getEnumParameter("ignoredStatuses", i, SpotCheckMismatchIgnore.class)).collect(Collectors.toSet());
        LocalDateTime toDateTime = toDate == null ? LocalDateTime.now() : parseISODateTime(toDate, "toDate");
        LocalDateTime fromDateTime = fromDate == null ? startOfSessionYear(toDateTime) : parseISODateTime(fromDate, "fromDate");
        OrderBy order = getOrderBy(orderBy, sort);
        LimitOffset limitOffset = getLimitOffset(request, 10);

        MismatchQuery query = new MismatchQuery(ds, Collections.singleton(ct))
                .withMismatchStatuses(ms)
                .withIgnoredStatuses(igs)
                .withFromDate(fromDateTime)
                .withToDate(toDateTime)
                .withOrderBy(order);

        // Get any ref type for this datasource and contentType.
        SpotCheckRefType refType = SpotCheckRefType.get(ds, ct).get(0);
        PaginatedList<DeNormSpotCheckMismatch> mismatches = reportServiceMap.get(refType).getMismatches(query, limitOffset);
        List<MismatchView> mismatchViews = new ArrayList<>();
        for (DeNormSpotCheckMismatch mm : mismatches.getResults()) {
            mismatchViews.add(new MismatchView(mm));
        }
        return ListViewResponse.of(mismatchViews, mismatches.getTotal(), mismatches.getLimOff());
    }

    private LocalDateTime startOfSessionYear(LocalDateTime toDateTime) {
        return SessionYear.of(toDateTime.getYear()).asDateTimeRange().lowerEndpoint();
    }

    /**
     * Used to convert orderBy and sort request parameters into an OrderBy object.
     * Defaults to ordering by REFERENCE_DATE descending if orderByString and sortString are null.
     * When ordering by a field other than REFERENCE_DATE, a secondary order by on
     * REFERENCE_DATE desc is added so the most recent results are always displayed first.
     *
     * @param orderByString String representing a MismatchOrderBy value.
     * @param sortString String representing a SortOrder value.
     * @return An OrderBy representing the supplied orderByString and sortString, potentially
     * with a secondary order by of REFERENCE_DATE desc.
     * @throws gov.nysenate.openleg.controller.api.base.InvalidRequestParamEx if orderByString or sortString
     * are not valid values.
     */
    private OrderBy getOrderBy(String orderByString, String sortString) {
        MismatchOrderBy orderBy = orderByString == null
                ? MismatchOrderBy.REFERENCE_DATE
                : getEnumParameter("orderBy", orderByString, MismatchOrderBy.class);

        SortOrder sortOrder = sortString == null
                ? SortOrder.DESC
                : getEnumParameter("sort", sortString, SortOrder.class);

        if (orderBy != MismatchOrderBy.REFERENCE_DATE) {
            // Add secondary order by reference date
            return new OrderBy(orderBy.getColumnName(), sortOrder,
                    MismatchOrderBy.REFERENCE_DATE.getColumnName(), SortOrder.DESC);
        }
        return new OrderBy(orderBy.getColumnName(), sortOrder);
    }

    /**
     * Spotcheck Mismatch Summary API
     *
     * Get a summary of mismatch status counts for all content types for a specific datasource.
     *
     * Usage: (GET) /api/3/admin/spotcheck/mismatches/summary
     *
     * Request Parameters: datasource - string - The datasource to return summary information on.
     *                     summaryDateTime - string (ISO date) - optional - returns summary information as of this date time.
     *                                       Defaults to current date time.
     */
    @RequiresPermissions("admin:view")
    @RequestMapping(value = "/mismatches/summary", method = RequestMethod.GET)
    public BaseResponse getMismatchSummary(@RequestParam String datasource,
                                           @RequestParam(required = false) String summaryDateTime) {
        SpotCheckDataSource ds = getEnumParameter("datasource", datasource, SpotCheckDataSource.class);
        LocalDateTime sumDateTime = summaryDateTime == null ? LocalDateTime.now() : parseISODateTime(summaryDateTime, "summaryDateTime");
        MismatchSummary summary = getAnyReportService().getMismatchSummary(ds, sumDateTime);
        return new ViewObjectResponse<>(new MismatchSummaryView(summary));
    }

    /**
     * Spotcheck Mismatch Ignore API
     *
     * Set the ignore status of a particular mismatch
     *
     * Usage: (POST) /api/3/admin/spotcheck/mismatches/{mismatchId}/ignore
     *
     * Request Parameters: ignoreLevel - string - specifies desired ignore level or unsets ignore if null or not present
     *                                  @see SpotCheckMismatchIgnore
     */
    @RequestMapping(value = "/mismatches/{mismatchId:\\d+}/ignore", method = RequestMethod.POST)
    public BaseResponse setIgnoreStatus(@PathVariable int mismatchId, @RequestParam(required = false) String ignoreLevel) {
        SpotCheckMismatchIgnore ignoreStatus = ignoreLevel == null
                ? SpotCheckMismatchIgnore.NOT_IGNORED
                : getEnumParameter("ignoreLevel", ignoreLevel, SpotCheckMismatchIgnore.class);
        getAnyReportService().setMismatchIgnoreStatus(mismatchId, ignoreStatus);
        return new SimpleResponse(true, "ignore level set", "ignore-level-set");
    }

    /**
     * Spotcheck Mismatch Add Issue Id API
     *
     * Adds an issue id to a spotcheck mismatch
     *
     * Usage: (POST) /api/3/admin/spotcheck/mismatches/{mismatchId}/issue/{issueId}
     */
    @RequestMapping(value = "/mismatches/{mismatchId:\\d+}/issue/{issueId}", method = RequestMethod.GET)
    public BaseResponse addMismatchIssueId(@PathVariable int mismatchId, @PathVariable String issueId) {
        getAnyReportService().addIssueId(mismatchId, issueId);
        return new SimpleResponse(true, "issue id added", "issue-id-added");
    }

    /**
     * Spotcheck Mismatch update Issue Id API
     * @param mismatchId  mismatch id
     * @param issueId mismatch issues id separate by comma ,e.g 12,3,61
     * @return true
     *
     * Usage: (POST) /api/3/admin/spotcheck/mismatches/{mismatchId}/issue/{issueId}
     */
    @RequestMapping(value = "/mismatches/{mismatchId:\\d+}/issue/{issueId}", method = RequestMethod.POST)
    public BaseResponse updateMismatchIssueId(@PathVariable int mismatchId, @PathVariable String issueId) {
        getAnyReportService().updateIssueId(mismatchId, issueId);
        return new SimpleResponse(true, "issue id updated", "issue-id-updated");
    }

    /**
     * Spotcheck Mismatch Remove Issue Id API
     *
     * Removes an issue id to a spotcheck mismatch
     *
     * Usage: (DELETE) /api/3/admin/spotcheck/mismatch/{mismatchId}/issue/{issueId}
     */
    @RequestMapping(value = "/mismatch/{mismatchId:\\d+}/issue/{issueId}", method = RequestMethod.DELETE)
    public BaseResponse deleteMismatchIssueId(@PathVariable int mismatchId, @PathVariable String issueId) {
        getAnyReportService().deleteIssueId(mismatchId, issueId);
        return new SimpleResponse(true, "issue id deleted", "issue-id-deleted");
    }
    /**
     * Spotcheck Mismatch remove All Issue Id API
     *
     * Removes an issue id to a spotcheck mismatch
     *
     * Usage: (DELETE) /api/3/admin/spotcheck/mismatch/{mismatchId}/delete
     */
    @RequestMapping(value = "/mismatch/{mismatchId:\\d+}/delete", method = RequestMethod.DELETE)
    public BaseResponse deleteMismatchIssueId(@PathVariable int mismatchId) {
        getAnyReportService().deleteAllIssueId(mismatchId);
        return new SimpleResponse(true, "issue id deleted", "issue-id-deleted");
    }


    /**
     * Spotcheck Report Run API
     *
     * Attempts to run spotcheck reports for the given report types
     *
     * Usage: (GET) /api/3/admin/spotcheck/run
     *
     * Request Parameters: reportType - string[] or string (in path variable) - specifies which kinds of report summaries
     *                                  are retrieved - defaults to all
     *                                  @see SpotCheckRefType
     */
    @RequiresPermissions("admin:view")
    @RequestMapping(value = "/run")
    public BaseResponse runReports(@RequestParam String[] reportType) {
        Set<SpotCheckRefType> refTypes = getSpotcheckRefTypes(reportType, "reportType");
        refTypes.forEach(spotcheckRunService::runReports);
        return new ViewObjectResponse<>(ListView.ofStringList(
                refTypes.stream().map(SpotCheckRefType::toString).collect(Collectors.toList())),
                "spotcheck reports run");
    }

    /** --- Internal Methods --- */

    private SpotCheckReportService<?> getAnyReportService() {
        return reportServices.stream().findAny()
                .orElseThrow(() -> new IllegalStateException("No spotcheck report services found"));
    }

    private SpotCheckRefType getSpotcheckRefType(String parameter, String paramName) {
        SpotCheckRefType result = getEnumParameter(parameter, SpotCheckRefType.class, null);
        if (result == null) {
            result = getEnumParameterByValue(SpotCheckRefType.class, SpotCheckRefType::getByRefName,
                    SpotCheckRefType::getRefName, paramName, parameter);
        }
        return result;
    }

    private Set<SpotCheckRefType> getSpotcheckRefTypes(String[] parameters, String paramName) {
        return parameters == null
                ? EnumSet.allOf(SpotCheckRefType.class)
                : Arrays.asList(parameters).stream()
                        .map(param -> getSpotcheckRefType(param, paramName))
                        .collect(Collectors.toSet());
    }

    private Set<SpotCheckMismatchType> getSpotcheckMismatchTypes(String[] parameters, String paramName,
                                                                 Set<SpotCheckRefType> refTypes) {
        return parameters == null
                ? refTypes.stream()
                        .flatMap(refType -> SpotCheckMismatchType.getMismatchTypes(refType).stream())
                        .collect(Collectors.toSet())
                : Arrays.asList(parameters).stream()
                        .map(paramValue -> getEnumParameter(paramName, paramValue, SpotCheckMismatchType.class))
                        .collect(Collectors.toSet());
    }

}
