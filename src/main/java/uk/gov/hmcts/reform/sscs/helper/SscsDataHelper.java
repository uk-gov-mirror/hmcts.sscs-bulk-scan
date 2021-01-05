package uk.gov.hmcts.reform.sscs.helper;

import static org.springframework.util.ObjectUtils.isEmpty;
import static uk.gov.hmcts.reform.sscs.ccd.domain.State.READY_TO_LIST;
import static uk.gov.hmcts.reform.sscs.ccd.domain.State.VALID_APPEAL;
import static uk.gov.hmcts.reform.sscs.domain.validation.ValidationStatus.*;
import static uk.gov.hmcts.reform.sscs.service.CaseCodeService.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.CaseResponse;
import uk.gov.hmcts.reform.sscs.ccd.domain.*;
import uk.gov.hmcts.reform.sscs.domain.CaseEvent;
import uk.gov.hmcts.reform.sscs.domain.validation.ValidationStatus;
import uk.gov.hmcts.reform.sscs.model.dwp.OfficeMapping;
import uk.gov.hmcts.reform.sscs.service.AirLookupService;
import uk.gov.hmcts.reform.sscs.service.DwpAddressLookupService;
import uk.gov.hmcts.reform.sscs.validators.PostcodeValidator;

@Component
public class SscsDataHelper {

    private final CaseEvent caseEvent;

    private final List<String> offices;

    private final DwpAddressLookupService dwpAddressLookupService;

    private final AirLookupService airLookupService;

    private final PostcodeValidator postcodeValidator;


    public SscsDataHelper(CaseEvent caseEvent,
                          @Value("#{'${readyToList.offices}'.split(',')}") List<String> offices,
                          DwpAddressLookupService dwpAddressLookupService,
                          AirLookupService airLookupService,
                          PostcodeValidator postcodeValidator) {
        this.caseEvent = caseEvent;
        this.offices = offices;
        this.dwpAddressLookupService = dwpAddressLookupService;
        this.airLookupService = airLookupService;
        this.postcodeValidator = postcodeValidator;
    }

    public void addSscsDataToMap(Map<String, Object> appealData, Appeal appeal, List<SscsDocument> sscsDocuments, Subscriptions subscriptions, FormType formType) {
        appealData.put("appeal", appeal);
        appealData.put("sscsDocument", sscsDocuments);
        appealData.put("evidencePresent", hasEvidence(sscsDocuments));
        appealData.put("subscriptions", subscriptions);
        appealData.put("formType", formType);

        if (appeal != null) {
            if (appeal.getBenefitType() != null) {
                String benefitCode = generateBenefitCode(appeal.getBenefitType().getCode());
                String issueCode = generateIssueCode();

                appealData.put("benefitCode", benefitCode);
                appealData.put("issueCode", issueCode);
                appealData.put("caseCode", generateCaseCode(benefitCode, issueCode));

                if (appeal.getMrnDetails() != null && appeal.getMrnDetails().getDwpIssuingOffice() != null) {
                    String dwpRegionCentre = dwpAddressLookupService.getDwpRegionalCenterByBenefitTypeAndOffice(
                        appeal.getBenefitType().getCode(),
                        appeal.getMrnDetails().getDwpIssuingOffice());

                    appealData.put("dwpRegionalCentre", dwpRegionCentre);
                }
            }
            appealData.put("createdInGapsFrom", getCreatedInGapsFromField(appeal));
        }
    }

    public String findEventToCreateCase(CaseResponse caseValidationResponse) {
        LocalDate mrnDate = findMrnDateTime(((Appeal) caseValidationResponse.getTransformedCase()
            .get("appeal")).getMrnDetails());

        if (!isEmpty(caseValidationResponse.getWarnings())) {
            return caseEvent.getIncompleteApplicationEventId();
        } else if (mrnDate != null && mrnDate.plusMonths(13L).isBefore(LocalDate.now())) {
            return caseEvent.getNonCompliantEventId();
        } else {
            return caseEvent.getValidAppealCreatedEventId();
        }
    }

    private LocalDate findMrnDateTime(MrnDetails mrnDetails) {
        if (mrnDetails != null && mrnDetails.getMrnDate() != null) {
            return LocalDate.parse(mrnDetails.getMrnDate());
        }
        return null;
    }

    public String hasEvidence(List<SscsDocument> sscsDocuments) {
        return (null == sscsDocuments || sscsDocuments.isEmpty()) ? "No" : "Yes";
    }

    public String getCreatedInGapsFromField(Appeal appeal) {

        if (null != appeal
                && null != appeal.getMrnDetails()
                && null != appeal.getMrnDetails().getDwpIssuingOffice()
                && null != appeal.getBenefitType()) {
            Optional<OfficeMapping> officeMapping = dwpAddressLookupService.getDwpMappingByOffice(appeal.getBenefitType().getCode(), appeal.getMrnDetails().getDwpIssuingOffice());

            return officeMapping.isPresent() && offices.contains(officeMapping.get().getCode()) ? READY_TO_LIST.getId() : VALID_APPEAL.getId();
        }
        return null;
    }

    public static ValidationStatus getValidationStatus(List<String> errors, List<String> warnings) {
        if (!ObjectUtils.isEmpty(errors)) {
            return ERRORS;
        }
        if (!ObjectUtils.isEmpty(warnings)) {
            return WARNINGS;
        }
        return SUCCESS;
    }

    public String findProcessingVenue(Appellant appellant, BenefitType benefitType) {
        if (appellant != null) {
            Appointee appointee = appellant.getAppointee();
            String postcode = null;
            if (appointee != null && appointee.getAddress() != null && isValidPostcode(appointee.getAddress().getPostcode())) {
                postcode = appointee.getAddress().getPostcode();
            } else if (appellant.getAddress() != null && isValidPostcode(appellant.getAddress().getPostcode())) {
                postcode = appellant.getAddress().getPostcode();
            }

            if (StringUtils.isNotEmpty(postcode)) {
                String venue = airLookupService.lookupAirVenueNameByPostCode(postcode, benefitType);
                return venue;
            }
        }
        return null;
    }

    private boolean isValidPostcode(String postcode) {
        return postcodeValidator.isValidPostcodeFormat(postcode) && postcodeValidator.isValid(postcode);
    }

}
