package uk.gov.hmcts.reform.sscs.bulkscancore.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.sscs.common.TestHelper.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.collect.ImmutableList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;
import org.assertj.core.util.Lists;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.CaseResponse;
import uk.gov.hmcts.reform.sscs.bulkscancore.domain.ExceptionRecord;
import uk.gov.hmcts.reform.sscs.bulkscancore.transformers.CaseTransformer;
import uk.gov.hmcts.reform.sscs.bulkscancore.validators.CaseValidator;
import uk.gov.hmcts.reform.sscs.ccd.callback.Callback;
import uk.gov.hmcts.reform.sscs.ccd.callback.PreSubmitCallbackResponse;
import uk.gov.hmcts.reform.sscs.ccd.domain.*;
import uk.gov.hmcts.reform.sscs.domain.CaseEvent;
import uk.gov.hmcts.reform.sscs.domain.SscsCaseDetails;
import uk.gov.hmcts.reform.sscs.domain.transformation.SuccessfulTransformationResponse;
import uk.gov.hmcts.reform.sscs.exceptions.InvalidExceptionRecordException;
import uk.gov.hmcts.reform.sscs.helper.SscsDataHelper;
import uk.gov.hmcts.reform.sscs.idam.IdamTokens;
import uk.gov.hmcts.reform.sscs.service.AirLookupService;
import uk.gov.hmcts.reform.sscs.service.DwpAddressLookupService;
import uk.gov.hmcts.reform.sscs.validators.PostcodeValidator;

@RunWith(JUnitParamsRunner.class)
public class CcdCallbackHandlerTest {

    @ClassRule
    public static final SpringClassRule springClassRule = new SpringClassRule();

    @Rule
    public final SpringMethodRule springMethodRule = new SpringMethodRule();

    private CcdCallbackHandler ccdCallbackHandler;

    @Mock
    private CaseTransformer caseTransformer;

    @Mock
    private CaseValidator caseValidator;

    @Mock
    private DwpAddressLookupService dwpAddressLookupService;

    @Mock
    private AirLookupService airLookupService;

    @Mock
    private PostcodeValidator postcodeValidator;

    private SscsDataHelper sscsDataHelper;

    @Captor
    private ArgumentCaptor<CaseResponse> warningCaptor;

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Map<String, Object> transformedCase;

    private IdamTokens idamTokens;

    private ListAppender<ILoggingEvent> listAppender;

    @Before
    public void setUp() {
        Logger fooLogger = (Logger) LoggerFactory.getLogger(CcdCallbackHandler.class);

        listAppender = new ListAppender<>();
        // create and start a ListAppender
        listAppender.start();
        // add the appender to the logger
        fooLogger.addAppender(listAppender);

        sscsDataHelper = new SscsDataHelper(new CaseEvent(null, "validAppealCreated", null, null), dwpAddressLookupService, airLookupService, postcodeValidator);
        ccdCallbackHandler = new CcdCallbackHandler(caseTransformer, caseValidator, sscsDataHelper, dwpAddressLookupService);

        idamTokens = IdamTokens.builder().idamOauth2Token(TEST_USER_AUTH_TOKEN).serviceAuthorization(TEST_SERVICE_AUTH_TOKEN).userId(TEST_USER_ID).build();

        given(dwpAddressLookupService.getDwpRegionalCenterByBenefitTypeAndOffice("PIP", "3"))
            .willReturn("Springburn");
        given(dwpAddressLookupService.getDwpRegionalCenterByBenefitTypeAndOffice("ESA", "Balham DRT"))
            .willReturn("Balham");

        given(airLookupService.lookupAirVenueNameByPostCode(anyString(), any(BenefitType.class))).willReturn("Cardiff");
        given(postcodeValidator.isValid(anyString())).willReturn(true);
        given(postcodeValidator.isValidPostcodeFormat(anyString())).willReturn(true);

        LocalDate localDate = LocalDate.now();

        Appeal appeal = Appeal.builder().mrnDetails(MrnDetails.builder().mrnDate(localDate.format(formatter)).build()).build();
        transformedCase = new HashMap<>();
        transformedCase.put("appeal", appeal);
    }

    @Test
    public void should_return_exception_data_with_case_id_and_state_when_transformation_and_validation_are_successful() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().build();

        CaseResponse response = CaseResponse.builder().transformedCase(transformedCase).build();

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false)).thenReturn(response);

        // No errors and warnings are populated hence validation would be successful
        CaseResponse caseValidationResponse = CaseResponse.builder().transformedCase(transformedCase).build();
        when(caseValidator.validateExceptionRecord(response, exceptionRecord, transformedCase, false))
            .thenReturn(caseValidationResponse);

        SuccessfulTransformationResponse ccdCallbackResponse = invokeCallbackHandler(exceptionRecord);

        assertExceptionDataEntries(ccdCallbackResponse);
        assertThat(ccdCallbackResponse.getWarnings()).isNull();
    }

    @Test(expected = InvalidExceptionRecordException.class)
    public void should_return_exception_record_and_errors_in_callback_response_when_transformation_fails() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().build();

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false))
            .thenReturn(CaseResponse.builder()
                .errors(ImmutableList.of("Cannot transform Appellant Date of Birth. Please enter valid date"))
                .build());

        invokeCallbackHandler(exceptionRecord);
    }

    @Test(expected = InvalidExceptionRecordException.class)
    public void should_return_exc_data_and_errors_in_callback_when_transformation_success_and_validation_fails_with_errors() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().build();

        CaseResponse response = CaseResponse.builder().transformedCase(transformedCase).build();

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false)).thenReturn(response);

        when(caseValidator.validateExceptionRecord(response, exceptionRecord, transformedCase, false))
            .thenReturn(CaseResponse.builder()
                .errors(ImmutableList.of("NI Number is invalid"))
                .build());
        try {
            invokeCallbackHandler(exceptionRecord);
        } catch (InvalidExceptionRecordException e) {
            assertLogContains("Errors found while validating exception record id null - NI Number is invalid");
            throw e;
        }
    }

    @Test(expected = InvalidExceptionRecordException.class)
    public void should_return_exc_data_and_warning_in_callback_when_is_automated_process_true_and_transformation_success_and_validation_fails_with_warning() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().isAutomatedProcess(true).build();
        ImmutableList<String> warningList = ImmutableList.of("office is missing");

        CaseResponse response = CaseResponse.builder().warnings(warningList).transformedCase(transformedCase).build();

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false)).thenReturn(response);

        when(caseValidator.validateExceptionRecord(response, exceptionRecord, transformedCase, false))
            .thenReturn(CaseResponse.builder()
                .warnings(warningList)
                .build());

        invokeCallbackHandler(exceptionRecord);
    }

    @Test
    public void givenAWarningInTransformationServiceAndAnotherWarningInValidationService_thenShowBothWarnings() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().build();

        List<String> warnings = new ArrayList<>();
        warnings.add("First warning");

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false))
            .thenReturn(CaseResponse.builder()
                .transformedCase(transformedCase)
                .warnings(warnings)
                .build());

        List<String> warnings2 = new ArrayList<>();
        warnings2.add("First warning");
        warnings2.add("Second warning");

        CaseResponse caseValidationResponse = CaseResponse.builder().warnings(warnings2).transformedCase(transformedCase).build();

        when(caseValidator.validateExceptionRecord(any(), eq(exceptionRecord), eq(transformedCase), eq(false)))
            .thenReturn(caseValidationResponse);

        SuccessfulTransformationResponse ccdCallbackResponse = invokeCallbackHandler(exceptionRecord);

        verify(caseValidator).validateExceptionRecord(warningCaptor.capture(), eq(exceptionRecord), eq(transformedCase), eq(false));

        assertThat(warningCaptor.getAllValues().get(0).getWarnings().size()).isEqualTo(1);
        assertThat(warningCaptor.getAllValues().get(0).getWarnings().get(0)).isEqualTo("First warning");

        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(2);
    }

    @Test(expected = InvalidExceptionRecordException.class)
    public void givenAWarningInValidationServiceWhenIsAutomatedProcessIsTrue_thenShowWarnings() {
        ExceptionRecord exceptionRecord = ExceptionRecord.builder().isAutomatedProcess(true).build();

        List<String> warnings = new ArrayList<>();

        when(caseTransformer.transformExceptionRecord(exceptionRecord, false))
            .thenReturn(CaseResponse.builder()
                .transformedCase(transformedCase)
                .warnings(warnings)
                .build());

        List<String> warnings2 = new ArrayList<>();
        warnings2.add("Second warning");

        CaseResponse caseValidationResponse = CaseResponse.builder().warnings(warnings2).transformedCase(transformedCase).build();

        when(caseValidator.validateExceptionRecord(any(), eq(exceptionRecord), eq(transformedCase), eq(false)))
            .thenReturn(caseValidationResponse);

        SuccessfulTransformationResponse ccdCallbackResponse = invokeCallbackHandler(exceptionRecord);
        // should not be called
        assertThat(true).isFalse();
    }

    @Test
    public void should_return_no_warnings_or_errors_or_data_when_validation_endpoint_is_successful_for_pip_case() {

        Appeal appeal = Appeal.builder()
            .appellant(Appellant.builder()
                .name(Name.builder().firstName("Fred").lastName("Ward").build())
                .identity(Identity.builder().nino("JT123456N").dob("12/08/1990").build())
                .address(Address.builder().postcode("CV35 2TD").build())
                .build())
            .mrnDetails(MrnDetails.builder().dwpIssuingOffice("3").build())
            .benefitType(BenefitType.builder().code("PIP").build())
            .build();

        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().appeal(appeal).interlocReviewState("something").build())
            .state("ScannedRecordReceived")
            .caseId("1234")
            .build();

        CaseResponse caseValidationResponse = CaseResponse.builder().build();
        when(caseValidator.validateValidationRecord(any(), anyBoolean())).thenReturn(caseValidationResponse);

        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        assertThat(ccdCallbackResponse.getData()).isNotNull();
        assertThat(ccdCallbackResponse.getErrors().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getData().getInterlocReviewState()).isEqualTo("none");
        assertThat(ccdCallbackResponse.getData().getCreatedInGapsFrom()).isEqualTo("readyToList");
        assertThat(ccdCallbackResponse.getData().getEvidencePresent()).isEqualTo("No");
        assertThat(ccdCallbackResponse.getData().getBenefitCode()).isEqualTo("002");
        assertThat(ccdCallbackResponse.getData().getIssueCode()).isEqualTo("DD");
        assertThat(ccdCallbackResponse.getData().getCaseCode()).isEqualTo("002DD");
        assertThat(ccdCallbackResponse.getData().getDwpRegionalCentre()).isEqualTo("Springburn");
        assertThat(ccdCallbackResponse.getData().getProcessingVenue()).isEqualTo("Cardiff");
    }

    @Test
    public void should_return_no_warnings_or_errors_or_data_when_validation_endpoint_is_successful_for_esa_case() {

        Appeal appeal = Appeal.builder()
            .appellant(Appellant.builder()
                .name(Name.builder().firstName("Fred").lastName("Ward").build())
                .identity(Identity.builder().nino("JT123456N").dob("12/08/1990").build())
                .build())
            .mrnDetails(MrnDetails.builder().dwpIssuingOffice("Balham DRT").build())
            .benefitType(BenefitType.builder().code("ESA").build())
            .build();

        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().appeal(appeal).interlocReviewState("something").build())
            .state("ScannedRecordReceived")
            .caseId("1234")
            .build();

        CaseResponse caseValidationResponse = CaseResponse.builder().build();
        when(caseValidator.validateValidationRecord(any(), anyBoolean())).thenReturn(caseValidationResponse);

        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        assertThat(ccdCallbackResponse.getData()).isNotNull();
        assertThat(ccdCallbackResponse.getErrors().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getData().getInterlocReviewState()).isEqualTo("none");
        assertThat(ccdCallbackResponse.getData().getCreatedInGapsFrom()).isEqualTo("readyToList");
        assertThat(ccdCallbackResponse.getData().getEvidencePresent()).isEqualTo("No");
        assertThat(ccdCallbackResponse.getData().getBenefitCode()).isEqualTo("051");
        assertThat(ccdCallbackResponse.getData().getIssueCode()).isEqualTo("DD");
        assertThat(ccdCallbackResponse.getData().getCaseCode()).isEqualTo("051DD");
        assertThat(ccdCallbackResponse.getData().getDwpRegionalCentre()).isEqualTo("Balham");
    }

    @Test
    public void should_return_warnings_or_error_on_data_when_direction_issued_and_mrn_date_is_empty_for_esa_case() {

        DynamicList appealToProccedDynamicList = new DynamicList(new DynamicListItem("appealToProceed", "appealToProceed"), new ArrayList<>());
        Appeal appeal = Appeal.builder()
            .appellant(Appellant.builder()
                .name(Name.builder().firstName("Fred").lastName("Ward").build())
                .identity(Identity.builder().nino("JT123456N").dob("12/08/1990").build())
                .build())
            .mrnDetails(MrnDetails.builder().mrnDate("").dwpIssuingOffice("Balham DRT").build())
            .benefitType(BenefitType.builder().code("ESA").build())
            .build();

        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().appeal(appeal).interlocReviewState("something").build())
            .state("ScannedRecordReceived")
            .caseId("1234")
            .build();

        caseDetails.getCaseData().setDirectionTypeDl(appealToProccedDynamicList);
        CaseResponse caseValidationResponse = CaseResponse.builder().build();
        when(caseValidator.validateValidationRecord(any(), eq(true))).thenReturn(caseValidationResponse);

        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData(), EventType.DIRECTION_ISSUED);

        assertThat(ccdCallbackResponse.getData()).isNotNull();
        assertThat(ccdCallbackResponse.getErrors().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getData().getInterlocReviewState()).isEqualTo("none");
        assertThat(ccdCallbackResponse.getData().getCreatedInGapsFrom()).isEqualTo("readyToList");
        assertThat(ccdCallbackResponse.getData().getEvidencePresent()).isEqualTo("No");
        assertThat(ccdCallbackResponse.getData().getBenefitCode()).isEqualTo("051");
        assertThat(ccdCallbackResponse.getData().getIssueCode()).isEqualTo("DD");
        assertThat(ccdCallbackResponse.getData().getCaseCode()).isEqualTo("051DD");
        assertThat(ccdCallbackResponse.getData().getDwpRegionalCentre()).isEqualTo("Balham");
    }

    @Test
    public void should_return_warnings_or_error_on_data_when_direction_issued_welsh_and_mrn_date_is_empty_for_esa_case() {

        DynamicList appealToProccedDynamicList = new DynamicList(new DynamicListItem("appealToProceed", "appealToProceed"), new ArrayList<>());
        Appeal appeal = Appeal.builder()
            .appellant(Appellant.builder()
                .name(Name.builder().firstName("Fred").lastName("Ward").build())
                .identity(Identity.builder().nino("JT123456N").dob("12/08/1990").build())
                .build())
            .mrnDetails(MrnDetails.builder().mrnDate("").dwpIssuingOffice("Balham DRT").build())
            .benefitType(BenefitType.builder().code("ESA").build())
            .build();

        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().appeal(appeal).interlocReviewState("something").build())
            .state("ScannedRecordReceived")
            .caseId("1234")
            .build();

        caseDetails.getCaseData().setDirectionTypeDl(appealToProccedDynamicList);
        CaseResponse caseValidationResponse = CaseResponse.builder().build();
        when(caseValidator.validateValidationRecord(any(), eq(true))).thenReturn(caseValidationResponse);

        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData(), EventType.DIRECTION_ISSUED_WELSH);

        assertThat(ccdCallbackResponse.getData()).isNotNull();
        assertThat(ccdCallbackResponse.getErrors().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getData().getInterlocReviewState()).isEqualTo("none");
        assertThat(ccdCallbackResponse.getData().getCreatedInGapsFrom()).isEqualTo("readyToList");
        assertThat(ccdCallbackResponse.getData().getEvidencePresent()).isEqualTo("No");
        assertThat(ccdCallbackResponse.getData().getBenefitCode()).isEqualTo("051");
        assertThat(ccdCallbackResponse.getData().getIssueCode()).isEqualTo("DD");
        assertThat(ccdCallbackResponse.getData().getCaseCode()).isEqualTo("051DD");
        assertThat(ccdCallbackResponse.getData().getDwpRegionalCentre()).isEqualTo("Balham");
    }

    @Test
    public void should_return_warnings_on_data_when_other_event_and_mrn_date_is_empty_for_esa_case() {

        Appeal appeal = Appeal.builder()
            .appellant(Appellant.builder()
                .name(Name.builder().firstName("Fred").lastName("Ward").build())
                .identity(Identity.builder().nino("JT123456N").dob("12/08/1990").build())
                .build())
            .mrnDetails(MrnDetails.builder().mrnDate("").dwpIssuingOffice("Balham DRT").build())
            .benefitType(BenefitType.builder().code("ESA").build())
            .build();

        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().ccdCaseId("123").appeal(appeal).interlocReviewState("something").build())
            .state("ScannedRecordReceived")
            .caseId("123")
            .build();

        CaseResponse caseValidationResponse = CaseResponse.builder().warnings(Lists.list("Mrn date is empty")).build();
        when(caseValidator.validateValidationRecord(any(), eq(false))).thenReturn(caseValidationResponse);

        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        assertThat(ccdCallbackResponse.getData()).isNotNull();
        assertThat(ccdCallbackResponse.getErrors().size()).isEqualTo(1);
        assertThat(ccdCallbackResponse.getErrors()).contains("Mrn date is empty");
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertThat(ccdCallbackResponse.getData().getInterlocReviewState()).isEqualTo("none");
        assertThat(ccdCallbackResponse.getData().getCreatedInGapsFrom()).isEqualTo("readyToList");
        assertThat(ccdCallbackResponse.getData().getEvidencePresent()).isEqualTo("No");
        assertThat(ccdCallbackResponse.getData().getBenefitCode()).isEqualTo("051");
        assertThat(ccdCallbackResponse.getData().getIssueCode()).isEqualTo("DD");
        assertThat(ccdCallbackResponse.getData().getCaseCode()).isEqualTo("051DD");
        assertThat(ccdCallbackResponse.getData().getDwpRegionalCentre()).isEqualTo("Balham");
        assertLogContains("Warnings found while validating exception record id 123 - Mrn date is empty");
    }

    @Test
    public void should_return_exc_data_and_errors_in_callback_when_validation_endpoint_fails_with_errors() {
        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().ccdCaseId("123").build())
            .state("ScannedRecordReceived")
            .caseId("123")
            .build();

        when(caseValidator.validateValidationRecord(any(), anyBoolean()))
            .thenReturn(CaseResponse.builder()
                .errors(ImmutableList.of("NI Number is invalid"))
                .build());

        // when
        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        // then
        assertThat(ccdCallbackResponse.getErrors()).containsOnly("NI Number is invalid");
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertLogContains("Errors found while validating exception record id 123 - NI Number is invalid");
    }

    @Test
    public void should_return_exc_data_and_errors_in_callback_when_validation_endpoint_fails_with_warnings() {
        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().ccdCaseId("123").build())
            .state("ScannedRecordReceived")
            .caseId("123")
            .build();

        when(caseValidator.validateValidationRecord(any(), anyBoolean()))
            .thenReturn(CaseResponse.builder()
                .warnings(ImmutableList.of("Postcode is invalid"))
                .build());

        // when
        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        // then
        assertThat(ccdCallbackResponse.getErrors()).containsOnly("Postcode is invalid");
        assertThat(ccdCallbackResponse.getWarnings().size()).isEqualTo(0);
        assertLogContains("Warnings found while validating exception record id 123 - Postcode is invalid");
    }

    @Test
    @Parameters({"", " ", "null", "Invalid"})
    public void should_return_error_for_invalid_benefitType(@Nullable String benefitType) {
        SscsCaseDetails caseDetails = SscsCaseDetails
            .builder()
            .caseData(SscsCaseData.builder().appeal(Appeal.builder().benefitType(BenefitType.builder().code(benefitType).build()).build()).benefitCode(benefitType).ccdCaseId("123").build())
            .state("ScannedRecordReceived")
            .caseId("123")
            .build();

        when(caseValidator.validateValidationRecord(any(), anyBoolean()))
            .thenReturn(CaseResponse.builder()
                .errors(ImmutableList.of("Benefit type is invalid"))
                .build());
        // when
        PreSubmitCallbackResponse<SscsCaseData> ccdCallbackResponse = invokeValidationCallbackHandler(caseDetails.getCaseData());

        // then
        assertThat(ccdCallbackResponse.getErrors()).containsOnly("Benefit type is invalid");
        assertThat(ccdCallbackResponse.getWarnings().size()).isZero();
    }

    private void assertLogContains(final String logMessage) {
        assertThat(listAppender.list.stream().map(ILoggingEvent::getFormattedMessage)).contains(logMessage);
    }

    private void assertExceptionDataEntries(SuccessfulTransformationResponse successfulTransformationResponse) {
        assertThat(successfulTransformationResponse.getCaseCreationDetails().getCaseTypeId()).isEqualTo("Benefit");
        assertThat(successfulTransformationResponse.getCaseCreationDetails().getEventId()).isEqualTo("validAppealCreated");
        assertThat(successfulTransformationResponse.getCaseCreationDetails().getCaseData()).isEqualTo(transformedCase);
    }

    private SuccessfulTransformationResponse invokeCallbackHandler(ExceptionRecord exceptionRecord) {
        return ccdCallbackHandler.handle(exceptionRecord);
    }

    private PreSubmitCallbackResponse<SscsCaseData> invokeValidationCallbackHandler(SscsCaseData caseDetails) {
        return invokeValidationCallbackHandler(caseDetails, EventType.VALID_APPEAL);
    }

    private PreSubmitCallbackResponse<SscsCaseData> invokeValidationCallbackHandler(SscsCaseData caseDetails, EventType eventType) {
        uk.gov.hmcts.reform.sscs.ccd.domain.CaseDetails<SscsCaseData> c = new uk.gov.hmcts.reform.sscs.ccd.domain.CaseDetails<>(123L, "sscs",
            State.INTERLOCUTORY_REVIEW_STATE, caseDetails, LocalDateTime.now());

        return ccdCallbackHandler.handleValidationAndUpdate(
            new Callback<>(c, Optional.empty(), eventType, false), idamTokens);
    }
}
