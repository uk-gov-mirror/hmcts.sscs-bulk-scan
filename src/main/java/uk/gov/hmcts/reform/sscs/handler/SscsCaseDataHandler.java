package uk.gov.hmcts.reform.sscs.handler;

import static org.springframework.util.ObjectUtils.isEmpty;
import static uk.gov.hmcts.reform.sscs.ccd.domain.EventType.SEND_TO_DWP;

import feign.FeignException;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import uk.gov.hmcts.reform.ccd.client.model.CallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.sscs.bulkscancore.ccd.CaseDataHelper;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.CaseResponse;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.ExceptionCaseData;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.HandlerResponse;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.Token;
import uk.gov.hmcts.reform.sscs.bulkscancore.handlers.CaseDataHandler;
import uk.gov.hmcts.reform.sscs.ccd.domain.*;
import uk.gov.hmcts.reform.sscs.domain.CaseEvent;
import uk.gov.hmcts.reform.sscs.exceptions.CaseDataHelperException;
import uk.gov.hmcts.reform.sscs.helper.SscsDataHelper;


@Component
@Slf4j
public class SscsCaseDataHandler implements CaseDataHandler {

    private static final String INTERLOC_REFERRAL_REASON = "interlocReferralReason";
    private final SscsDataHelper sscsDataHelper;
    private final CaseDataHelper caseDataHelper;
    private final CaseEvent caseEvent;

    public SscsCaseDataHandler(SscsDataHelper sscsDataHelper,
                               CaseDataHelper caseDataHelper,
                               CaseEvent caseEvent) {
        this.sscsDataHelper = sscsDataHelper;
        this.caseDataHelper = caseDataHelper;
        this.caseEvent = caseEvent;
    }

    public CallbackResponse handle(ExceptionCaseData exceptionCaseData,
                                   CaseResponse caseValidationResponse,
                                   boolean ignoreWarnings,
                                   Token token,
                                   String exceptionRecordId) {

        if (canCreateCase(caseValidationResponse, ignoreWarnings)) {
            boolean isCaseAlreadyExists = false;
            String eventId = sscsDataHelper.findEventToCreateCase(caseValidationResponse);
            stampReferredCase(caseValidationResponse, eventId);

            String caseReference = String.valueOf(Optional.ofNullable(
                exceptionCaseData.getCaseDetails().getCaseData().get("caseReference")).orElse(""));
            Appeal appeal = (Appeal) caseValidationResponse.getTransformedCase().get("appeal");
            String mrnDate = "";
            String benefitType = "";
            String nino = "";

            if (appeal != null) {
                if (appeal.getMrnDetails() != null) {
                    mrnDate = appeal.getMrnDetails().getMrnDate();
                }
                if (appeal.getBenefitType() != null) {
                    benefitType = appeal.getBenefitType().getCode();
                }
                if (appeal.getAppellant() != null && appeal.getAppellant().getIdentity() != null) {
                    nino = appeal.getAppellant().getIdentity().getNino();
                }
            }

            if (!StringUtils.isEmpty(caseReference)) {
                log.info("Case {} already exists for exception record id {}", caseReference, exceptionRecordId);
                isCaseAlreadyExists = true;
            } else if (!StringUtils.isEmpty(nino) && !StringUtils.isEmpty(benefitType)
                && !StringUtils.isEmpty(mrnDate)) {
                Map<String, String> searchCriteria = new HashMap<>();
                searchCriteria.put("case.appeal.appellant.identity.nino", nino);
                searchCriteria.put("case.appeal.benefitType.code", benefitType);
                searchCriteria.put("case.appeal.mrnDetails.mrnDate", mrnDate);

                List<CaseDetails> caseDetails = caseDataHelper.findCaseBy(
                    searchCriteria, token.getUserAuthToken(), token.getServiceAuthToken(), token.getUserId());

                if (!CollectionUtils.isEmpty(caseDetails)) {
                    log.info("Duplicate case found for Nino {} , benefit type {} and mrnDate {}. "
                            + "No need to continue with post create case processing.",
                        nino, benefitType, mrnDate);
                    isCaseAlreadyExists = true;
                    caseReference = String.valueOf(caseDetails.get(0).getId());
                }
            }

            try {
                if (!isCaseAlreadyExists) {
                    Map<String, Object> sscsCaseData = caseValidationResponse.getTransformedCase();

                    sscsCaseData = checkForMatches(nino, sscsCaseData, token);

                    Long caseId = caseDataHelper.createCase(sscsCaseData,
                        token.getUserAuthToken(), token.getServiceAuthToken(), token.getUserId(), eventId);

                    log.info("Case created with caseId {} from exception record id {}", caseId, exceptionRecordId);

                    if (isCaseCreatedEvent(eventId)) {
                        log.info("About to update case with sendToDwp event for id {}", caseId);
                        caseDataHelper.updateCase(caseValidationResponse.getTransformedCase(), token.getUserAuthToken(),
                            token.getServiceAuthToken(), token.getUserId(), SEND_TO_DWP.getCcdType(), caseId,
                            "Send to DWP", "Send to DWP event has been triggered from Bulk Scan service");
                        log.info("Case updated with sendToDwp event for id {}", caseId);
                    }
                    caseReference = String.valueOf(caseId);
                }


                return HandlerResponse.builder().state("ScannedRecordCaseCreated").caseId(caseReference).build();
            } catch (FeignException e) {
                throw e;
            } catch (Exception e) {
                wrapAndThrowCaseDataHandlerException(exceptionRecordId, e);
            }
        }
        return null;
    }

    protected Map<String, Object> checkForMatches(String nino, Map<String, Object> sscsCaseData, Token token) {
        List<CaseDetails> matchedByNinoCases = new ArrayList<>();
        if (nino != null && !nino.equals("")) {
            Map<String, String> linkCasesCriteria = new HashMap<>();
            linkCasesCriteria.put("case.appeal.appellant.identity.nino", nino);
            matchedByNinoCases = caseDataHelper.findCaseBy(linkCasesCriteria, token.getUserAuthToken(), token.getServiceAuthToken(), token.getUserId());
        }
        sscsCaseData = addAssociatedCases(sscsCaseData, matchedByNinoCases);
        return sscsCaseData;
    }

    protected Map<String, Object> addAssociatedCases(Map<String, Object> sscsCaseData, List<CaseDetails> matchedByNinoCases) {
        List<CaseLink> associatedCases = new ArrayList<>();

        for (CaseDetails sscsCaseDetails : matchedByNinoCases) {
            CaseLink caseLink = CaseLink.builder().value(
                CaseLinkDetails.builder().caseReference(sscsCaseDetails.getId().toString()).build()).build();
            associatedCases.add(caseLink);
            log.info("Added associated case " + sscsCaseDetails.getId().toString());
        }
        if (associatedCases.size() > 0) {
            sscsCaseData.put("associatedCase", associatedCases);
            sscsCaseData.put("linkedCasesBoolean", "Yes");
        } else {
            sscsCaseData.put("linkedCasesBoolean", "No");
        }

        return sscsCaseData;
    }

    private void stampReferredCase(CaseResponse caseValidationResponse, String eventId) {
        Map<String, Object> transformedCase = caseValidationResponse.getTransformedCase();
        Appeal appeal = (Appeal) transformedCase.get("appeal");
        if (EventType.NON_COMPLIANT.getCcdType().equals(eventId)) {
            if (appealReasonIsNotBlank(appeal)) {
                transformedCase.put(INTERLOC_REFERRAL_REASON,
                    InterlocReferralReasonOptions.OVER_13_MONTHS.getValue());
            } else {
                transformedCase.put(INTERLOC_REFERRAL_REASON,
                    InterlocReferralReasonOptions.OVER_13_MONTHS_AND_GROUNDS_MISSING.getValue());
            }
        }
    }

    private boolean appealReasonIsNotBlank(Appeal appeal) {
        return appeal.getAppealReasons() != null && (StringUtils.isNotBlank(appeal.getAppealReasons().getOtherReasons())
            || reasonsIsNotBlank(appeal));
    }

    private boolean reasonsIsNotBlank(Appeal appeal) {
        return !isEmpty(appeal.getAppealReasons().getReasons())
            && appeal.getAppealReasons().getReasons().get(0) != null
            && appeal.getAppealReasons().getReasons().get(0).getValue() != null
            && (StringUtils.isNotBlank(appeal.getAppealReasons().getReasons().get(0).getValue().getReason())
            || StringUtils.isNotBlank(appeal.getAppealReasons().getReasons().get(0).getValue().getDescription()));
    }

    private boolean isCaseCreatedEvent(String eventId) {
        return eventId.equals(caseEvent.getCaseCreatedEventId())
            || eventId.equals(caseEvent.getValidAppealCreatedEventId());
    }

    private Boolean canCreateCase(CaseResponse caseValidationResponse, boolean ignoreWarnings) {
        return ((!isEmpty(caseValidationResponse.getWarnings()) && ignoreWarnings)
            || isEmpty(caseValidationResponse.getWarnings()));
    }

    private void wrapAndThrowCaseDataHandlerException(String exceptionId, Exception ex) {
        CaseDataHelperException exception = new CaseDataHelperException(exceptionId, ex);
        log.error("Error for exception id: " + exceptionId, exception);
        throw exception;
    }
}
