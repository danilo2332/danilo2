package gov.nysenate.openleg.model.spotcheck;

/**
 * Enumeration of the different bill fields that we can check for data quality issues.
 */
public enum SpotCheckMismatchType
{
    /** --- Bill data mismatches --- */

    BILL_ACTION,
    BILL_ACTIVE_AMENDMENT,
    BILL_AMENDMENT_PUBLISH,
    BILL_COSPONSOR,
    BILL_FULLTEXT_PAGE_COUNT,
    BILL_LAW_CODE,
    BILL_LAW_CODE_SUMMARY,
    BILL_LAW_SECTION,
    BILL_MULTISPONSOR,
    BILL_SPONSOR,
    BILL_SPONSOR_MEMO,
    BILL_SAMEAS,
    BILL_SUMMARY,
    BILL_TITLE
}