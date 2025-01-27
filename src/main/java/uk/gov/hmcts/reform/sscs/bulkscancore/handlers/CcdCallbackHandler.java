package uk.gov.hmcts.reform.sscs.bulkscancore.handlers;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.springframework.util.ObjectUtils.isEmpty;
import static uk.gov.hmcts.reform.sscs.ccd.domain.State.READY_TO_LIST;
import static uk.gov.hmcts.reform.sscs.service.CaseCodeService.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.CaseResponse;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.ExceptionRecord;
import uk.gov.hmcts.reform.sscs.bulkscancore.transformers.CaseTransformer;
import uk.gov.hmcts.reform.sscs.bulkscancore.validators.CaseValidator;
import uk.gov.hmcts.reform.sscs.ccd.callback.Callback;
import uk.gov.hmcts.reform.sscs.ccd.callback.PreSubmitCallbackResponse;
import uk.gov.hmcts.reform.sscs.ccd.domain.*;
import uk.gov.hmcts.reform.sscs.domain.transformation.CaseCreationDetails;
import uk.gov.hmcts.reform.sscs.domain.transformation.SuccessfulTransformationResponse;
import uk.gov.hmcts.reform.sscs.exception.BenefitMappingException;
import uk.gov.hmcts.reform.sscs.exceptions.InvalidExceptionRecordException;
import uk.gov.hmcts.reform.sscs.handler.InterlocReferralReasonOptions;
import uk.gov.hmcts.reform.sscs.helper.SscsDataHelper;
import uk.gov.hmcts.reform.sscs.idam.IdamTokens;
import uk.gov.hmcts.reform.sscs.service.DwpAddressLookupService;

@Component
@Slf4j
public class CcdCallbackHandler {

    private static final String LOGSTR_VALIDATION_ERRORS = "Errors found while validating exception record id {} - {}";
    private static final String LOGSTR_VALIDATION_WARNING = "Warnings found while validating exception record id {} - {}";

    private final CaseTransformer caseTransformer;

    private final CaseValidator caseValidator;

    private final SscsDataHelper sscsDataHelper;

    private final DwpAddressLookupService dwpAddressLookupService;

    public static final String CASE_TYPE_ID = "Benefit";

    public CcdCallbackHandler(
        CaseTransformer caseTransformer,
        CaseValidator caseValidator,
        SscsDataHelper sscsDataHelper,
        DwpAddressLookupService dwpAddressLookupService
    ) {
        this.caseTransformer = caseTransformer;
        this.caseValidator = caseValidator;
        this.sscsDataHelper = sscsDataHelper;
        this.dwpAddressLookupService = dwpAddressLookupService;
    }

    public CaseResponse handleValidation(ExceptionRecord exceptionRecord) {

        log.info("Processing callback for SSCS exception record");

        CaseResponse caseTransformationResponse = caseTransformer.transformExceptionRecord(exceptionRecord, true);

        if (caseTransformationResponse.getErrors() != null && caseTransformationResponse.getErrors().size() > 0) {
            log.info("Errors found during validation");
            return caseTransformationResponse;
        }

        log.info("Exception record id {} transformed successfully ready for validation", exceptionRecord.getId());

        return caseValidator.validateExceptionRecord(caseTransformationResponse, exceptionRecord, caseTransformationResponse.getTransformedCase(), true);
    }

    public SuccessfulTransformationResponse handle(ExceptionRecord exceptionRecord) {
        // New transformation request contains exceptionRecordId
        // Old transformation request contains id field, which is the exception record id
        String exceptionRecordId = isNotBlank(exceptionRecord.getExceptionRecordId()) ? exceptionRecord.getExceptionRecordId() : exceptionRecord.getId();

        log.info("Processing callback for SSCS exception record id {}", exceptionRecordId);
        log.info("IsAutomatedProcess: {}", exceptionRecord.getIsAutomatedProcess());

        CaseResponse caseTransformationResponse = caseTransformer.transformExceptionRecord(exceptionRecord, false);

        if (caseTransformationResponse.getErrors() != null && caseTransformationResponse.getErrors().size() > 0) {
            log.info("Errors found while transforming exception record id {} - {}", exceptionRecordId, stringJoin(caseTransformationResponse.getErrors()));
            throw new InvalidExceptionRecordException(caseTransformationResponse.getErrors());
        }

        if (BooleanUtils.isTrue(exceptionRecord.getIsAutomatedProcess()) && !CollectionUtils.isEmpty(caseTransformationResponse.getWarnings())) {
            log.info("Warning found while transforming exception record id {}", exceptionRecordId);
            throw new InvalidExceptionRecordException(caseTransformationResponse.getWarnings());
        }

        log.info("Exception record id {} transformed successfully. About to validate transformed case from exception", exceptionRecordId);

        CaseResponse caseValidationResponse = caseValidator.validateExceptionRecord(caseTransformationResponse, exceptionRecord, caseTransformationResponse.getTransformedCase(), false);

        if (!ObjectUtils.isEmpty(caseValidationResponse.getErrors())) {
            log.info(LOGSTR_VALIDATION_ERRORS, exceptionRecordId, stringJoin(caseValidationResponse.getErrors()));
            throw new InvalidExceptionRecordException(caseValidationResponse.getErrors());
        } else if (BooleanUtils.isTrue(exceptionRecord.getIsAutomatedProcess()) && !ObjectUtils.isEmpty(caseValidationResponse.getWarnings())) {
            log.info(LOGSTR_VALIDATION_WARNING, exceptionRecordId, stringJoin(caseValidationResponse.getWarnings()));
            throw new InvalidExceptionRecordException(caseValidationResponse.getWarnings());
        } else {
            String eventId = sscsDataHelper.findEventToCreateCase(caseValidationResponse);

            stampReferredCase(caseValidationResponse, eventId);

            return new SuccessfulTransformationResponse(
                new CaseCreationDetails(
                    CASE_TYPE_ID,
                    eventId,
                    caseValidationResponse.getTransformedCase()
                ),
            caseValidationResponse.getWarnings());
        }
    }

    private String stringJoin(List<String> messages) {
        return String.join(". ", messages);
    }

    public PreSubmitCallbackResponse<SscsCaseData> handleValidationAndUpdate(Callback<SscsCaseData> callback, IdamTokens token) {
        log.info("Processing validation and update request for SSCS exception record id {}", callback.getCaseDetails().getId());

        if (null != callback.getCaseDetails().getCaseData().getInterlocReviewState()) {
            callback.getCaseDetails().getCaseData().setInterlocReviewState("none");
        }

        setUnsavedFieldsOnCallback(callback);

        Map<String, Object> appealData = new HashMap<>();
        sscsDataHelper.addSscsDataToMap(appealData,
            callback.getCaseDetails().getCaseData().getAppeal(),
            callback.getCaseDetails().getCaseData().getSscsDocument(),
            callback.getCaseDetails().getCaseData().getSubscriptions(),
            callback.getCaseDetails().getCaseData().getFormType());

        boolean ignoreMrnValidation = false;
        if (callback.getEvent() != null && (EventType.DIRECTION_ISSUED.equals(callback.getEvent())
            || EventType.DIRECTION_ISSUED_WELSH.equals(callback.getEvent()))
            && callback.getCaseDetails().getCaseData().getDirectionTypeDl() != null) {
            ignoreMrnValidation = StringUtils.equals(DirectionType.APPEAL_TO_PROCEED.toString(), callback.getCaseDetails().getCaseData().getDirectionTypeDl().getValue().getCode());
        }
        CaseResponse caseValidationResponse = caseValidator.validateValidationRecord(appealData, ignoreMrnValidation);

        PreSubmitCallbackResponse<SscsCaseData> validationErrorResponse = convertWarningsToErrors(callback.getCaseDetails().getCaseData(), caseValidationResponse);

        if (validationErrorResponse != null) {
            log.info(LOGSTR_VALIDATION_ERRORS, callback.getCaseDetails().getId(), ".");
            return validationErrorResponse;
        } else {
            log.info("Exception record id {} validated successfully", callback.getCaseDetails().getId());

            PreSubmitCallbackResponse<SscsCaseData> preSubmitCallbackResponse = new PreSubmitCallbackResponse<>(callback.getCaseDetails().getCaseData());

            if (caseValidationResponse.getWarnings() != null) {
                preSubmitCallbackResponse.addWarnings(caseValidationResponse.getWarnings());
            }

            caseValidationResponse.setTransformedCase(caseTransformer.checkForMatches(caseValidationResponse.getTransformedCase(), token));

            return preSubmitCallbackResponse;
        }
    }

    private void setUnsavedFieldsOnCallback(Callback<SscsCaseData> callback) {
        Appeal appeal = callback.getCaseDetails().getCaseData().getAppeal();
        callback.getCaseDetails().getCaseData().setCreatedInGapsFrom(READY_TO_LIST.getId());
        callback.getCaseDetails().getCaseData().setEvidencePresent(sscsDataHelper.hasEvidence(callback.getCaseDetails().getCaseData().getSscsDocument()));

        if (appeal != null && callback.getCaseDetails().getCaseData().getAppeal().getBenefitType() != null && isNotBlank(callback.getCaseDetails().getCaseData().getAppeal().getBenefitType().getCode())) {
            String benefitCode = null;
            try {
                benefitCode = generateBenefitCode(callback.getCaseDetails().getCaseData().getAppeal().getBenefitType().getCode());
            } catch (BenefitMappingException ignored) {
                //
            }
            String issueCode = generateIssueCode();

            callback.getCaseDetails().getCaseData().setBenefitCode(benefitCode);
            callback.getCaseDetails().getCaseData().setIssueCode(issueCode);
            callback.getCaseDetails().getCaseData().setCaseCode(generateCaseCode(benefitCode, issueCode));

            if (callback.getCaseDetails().getCaseData().getAppeal().getMrnDetails() != null
                && callback.getCaseDetails().getCaseData().getAppeal().getMrnDetails().getDwpIssuingOffice() != null) {

                String dwpRegionCentre = dwpAddressLookupService.getDwpRegionalCenterByBenefitTypeAndOffice(
                    appeal.getBenefitType().getCode(),
                    appeal.getMrnDetails().getDwpIssuingOffice());

                callback.getCaseDetails().getCaseData().setDwpRegionalCentre(dwpRegionCentre);
            }

            String processingVenue = sscsDataHelper.findProcessingVenue(appeal.getAppellant(), appeal.getBenefitType());
            if (isNotBlank(processingVenue)) {
                callback.getCaseDetails().getCaseData().setProcessingVenue(processingVenue);
            }
        }

    }

    private PreSubmitCallbackResponse<SscsCaseData> convertWarningsToErrors(SscsCaseData caseData, CaseResponse caseResponse) {

        List<String> appendedWarningsAndErrors = new ArrayList<>();

        if (!ObjectUtils.isEmpty(caseResponse.getWarnings())) {
            log.info(LOGSTR_VALIDATION_WARNING, caseData.getCcdCaseId(), stringJoin(caseResponse.getWarnings()));
            appendedWarningsAndErrors.addAll(caseResponse.getWarnings());
        }

        if (!ObjectUtils.isEmpty(caseResponse.getErrors())) {
            log.info(LOGSTR_VALIDATION_ERRORS, caseData.getCcdCaseId(), stringJoin(caseResponse.getErrors()));
            appendedWarningsAndErrors.addAll(caseResponse.getErrors());
        }

        if (!appendedWarningsAndErrors.isEmpty()) {
            PreSubmitCallbackResponse<SscsCaseData> preSubmitCallbackResponse = new PreSubmitCallbackResponse<>(caseData);

            preSubmitCallbackResponse.addErrors(appendedWarningsAndErrors);
            return preSubmitCallbackResponse;
        }
        return null;
    }

    private void stampReferredCase(CaseResponse caseValidationResponse, String eventId) {
        if (EventType.NON_COMPLIANT.getCcdType().equals(eventId)) {
            Map<String, Object> transformedCase = caseValidationResponse.getTransformedCase();
            Appeal appeal = (Appeal) transformedCase.get("appeal");
            if (appealReasonIsNotBlank(appeal)) {
                transformedCase.put("interlocReferralReason",
                    InterlocReferralReasonOptions.OVER_13_MONTHS.getValue());
            } else {
                transformedCase.put("interlocReferralReason",
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
}
