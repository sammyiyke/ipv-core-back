package uk.gov.di.ipv.core.evaluategpg45scores.gpg45;

import org.junit.jupiter.api.Test;
import uk.gov.di.ipv.core.evaluategpg45scores.exception.UnknownEvidenceTypeException;
import uk.gov.di.ipv.core.library.domain.JourneyResponse;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gpg45ProfileEvaluatorTest {

    private final Gpg45ProfileEvaluator evaluator = new Gpg45ProfileEvaluator();
    private final String M1A_PASSPORT_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJhdWQiOiJodHRwczpcL1wvaWRlbnRpdHkuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJuYmYiOjE2NTg4Mjk2NDcsImlzcyI6Imh0dHBzOlwvXC9yZXZpZXctcC5pbnRlZ3JhdGlvbi5hY2NvdW50Lmdvdi51ayIsImV4cCI6MTY1ODgzNjg0NywidmMiOnsiZXZpZGVuY2UiOlt7InZhbGlkaXR5U2NvcmUiOjIsInN0cmVuZ3RoU2NvcmUiOjQsImNpIjpudWxsLCJ0eG4iOiIxMjNhYjkzZC0zYTQzLTQ2ZWYtYTJjMS0zYzY0NDQyMDY0MDgiLCJ0eXBlIjoiSWRlbnRpdHlDaGVjayJ9XSwiY3JlZGVudGlhbFN1YmplY3QiOnsicGFzc3BvcnQiOlt7ImV4cGlyeURhdGUiOiIyMDMwLTAxLTAxIiwiZG9jdW1lbnROdW1iZXIiOiIzMjE2NTQ5ODcifV0sIm5hbWUiOlt7Im5hbWVQYXJ0cyI6W3sidHlwZSI6IkdpdmVuTmFtZSIsInZhbHVlIjoiS0VOTkVUSCJ9LHsidHlwZSI6IkZhbWlseU5hbWUiLCJ2YWx1ZSI6IkRFQ0VSUVVFSVJBIn1dfV0sImJpcnRoRGF0ZSI6W3sidmFsdWUiOiIxOTU5LTA4LTIzIn1dfSwidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl19fQ.MEYCIQC-2fwJVvFLM8SnCKk_5EHX_ZPdTN2-kaOxNjXky86LUgIhAIMZUuTztxyyqa3ZkyaqnkMl1vPl1HQ2FbQ9LxPQChn";
    private final String M1A_ADDRESS_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWEuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJuYmYiOjE2NTg4Mjk3MjAsImV4cCI6MTY1ODgzNjkyMCwidmMiOnsidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIkFkZHJlc3NDcmVkZW50aWFsIl0sIkBjb250ZXh0IjpbImh0dHBzOlwvXC93d3cudzMub3JnXC8yMDE4XC9jcmVkZW50aWFsc1wvdjEiLCJodHRwczpcL1wvdm9jYWIubG9uZG9uLmNsb3VkYXBwcy5kaWdpdGFsXC9jb250ZXh0c1wvaWRlbnRpdHktdjEuanNvbmxkIl0sImNyZWRlbnRpYWxTdWJqZWN0Ijp7ImFkZHJlc3MiOlt7InVwcm4iOjEwMDEyMDAxMjA3NywiYnVpbGRpbmdOdW1iZXIiOiI4IiwiYnVpbGRpbmdOYW1lIjoiIiwic3RyZWV0TmFtZSI6IkhBRExFWSBST0FEIiwiYWRkcmVzc0xvY2FsaXR5IjoiQkFUSCIsInBvc3RhbENvZGUiOiJCQTIgNUFBIiwiYWRkcmVzc0NvdW50cnkiOiJHQiIsInZhbGlkRnJvbSI6IjIwMDAtMDEtMDEifV19fX0.MEQCIDGSdiAuPOEQGRlU_SGRWkVYt28oCVAVIuVWkAseN_RCAiBsdf5qS5BIsAoaebo8L60yaUuZjxU9mYloBa24IFWYsw";
    private final String M1A_FRAUD_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWYuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJuYmYiOjE2NTg4Mjk3NTgsImV4cCI6MTY1ODgzNjk1OCwidmMiOnsiY3JlZGVudGlhbFN1YmplY3QiOnsibmFtZSI6W3sibmFtZVBhcnRzIjpbeyJ0eXBlIjoiR2l2ZW5OYW1lIiwidmFsdWUiOiJLRU5ORVRIIn0seyJ0eXBlIjoiRmFtaWx5TmFtZSIsInZhbHVlIjoiREVDRVJRVUVJUkEifV19XSwiYWRkcmVzcyI6W3siYWRkcmVzc0NvdW50cnkiOiJHQiIsImJ1aWxkaW5nTmFtZSI6IiIsInN0cmVldE5hbWUiOiJIQURMRVkgUk9BRCIsInBvQm94TnVtYmVyIjpudWxsLCJwb3N0YWxDb2RlIjoiQkEyIDVBQSIsImJ1aWxkaW5nTnVtYmVyIjoiOCIsImlkIjpudWxsLCJhZGRyZXNzTG9jYWxpdHkiOiJCQVRIIiwic3ViQnVpbGRpbmdOYW1lIjpudWxsfV0sImJpcnRoRGF0ZSI6W3sidmFsdWUiOiIxOTU5LTA4LTIzIn1dfSwidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl0sImV2aWRlbmNlIjpbeyJ0eXBlIjoiSWRlbnRpdHlDaGVjayIsInR4biI6IlJCMDAwMTAzNDkwMDg3IiwiaWRlbnRpdHlGcmF1ZFNjb3JlIjoxLCJjaSI6W119XX19.MEUCIHoe7TsSTTORaj2X5cpv7Fpg1gVenFwEhYL4tf6zt3eJAiEAiwqUTOROjTB-Gyxt-IEwUQNndj_L43dMAnrPRaWnzNE";
    private final String M1A_FRAUD_VC_WITH_A01 =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWYuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJuYmYiOjE2NTg4Mjk3NTgsImV4cCI6MTY1ODgzNjk1OCwidmMiOnsiY3JlZGVudGlhbFN1YmplY3QiOnsibmFtZSI6W3sibmFtZVBhcnRzIjpbeyJ0eXBlIjoiR2l2ZW5OYW1lIiwidmFsdWUiOiJLRU5ORVRIIn0seyJ0eXBlIjoiRmFtaWx5TmFtZSIsInZhbHVlIjoiREVDRVJRVUVJUkEifV19XSwiYWRkcmVzcyI6W3siYWRkcmVzc0NvdW50cnkiOiJHQiIsImJ1aWxkaW5nTmFtZSI6IiIsInN0cmVldE5hbWUiOiJIQURMRVkgUk9BRCIsInBvQm94TnVtYmVyIjpudWxsLCJwb3N0YWxDb2RlIjoiQkEyIDVBQSIsImJ1aWxkaW5nTnVtYmVyIjoiOCIsImlkIjpudWxsLCJhZGRyZXNzTG9jYWxpdHkiOiJCQVRIIiwic3ViQnVpbGRpbmdOYW1lIjpudWxsfV0sImJpcnRoRGF0ZSI6W3sidmFsdWUiOiIxOTU5LTA4LTIzIn1dfSwidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl0sImV2aWRlbmNlIjpbeyJ0eXBlIjoiSWRlbnRpdHlDaGVjayIsInR4biI6IlJCMDAwMTAzNDkwMDg3IiwiaWRlbnRpdHlGcmF1ZFNjb3JlIjoxLCJjaSI6WyJBMDEiXX1dfX0.MEUCIHoe7TsSTTORaj2X5cpv7Fpg1gVenFwEhYL4tf6zt3eJAiEAiwqUTOROjTB-Gyxt-IEwUQNndj_L43dMAnrPRaWnzNE";
    private final String M1A_KBV_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWsuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJuYmYiOjE2NTg4Mjk5MTcsImV4cCI6MTY1ODgzNzExNywidmMiOnsidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl0sImV2aWRlbmNlIjpbeyJ0eG4iOiI3TEFLUlRBN0ZTIiwidmVyaWZpY2F0aW9uU2NvcmUiOjIsInR5cGUiOiJJZGVudGl0eUNoZWNrIn1dLCJjcmVkZW50aWFsU3ViamVjdCI6eyJuYW1lIjpbeyJuYW1lUGFydHMiOlt7InR5cGUiOiJHaXZlbk5hbWUiLCJ2YWx1ZSI6IktFTk5FVEgifSx7InR5cGUiOiJGYW1pbHlOYW1lIiwidmFsdWUiOiJERUNFUlFVRUlSQSJ9XX1dLCJhZGRyZXNzIjpbeyJhZGRyZXNzQ291bnRyeSI6IkdCIiwiYnVpbGRpbmdOYW1lIjoiIiwic3RyZWV0TmFtZSI6IkhBRExFWSBST0FEIiwicG9zdGFsQ29kZSI6IkJBMiA1QUEiLCJidWlsZGluZ051bWJlciI6IjgiLCJhZGRyZXNzTG9jYWxpdHkiOiJCQVRIIn0seyJhZGRyZXNzQ291bnRyeSI6IkdCIiwidXBybiI6MTAwMTIwMDEyMDc3LCJidWlsZGluZ05hbWUiOiIiLCJzdHJlZXROYW1lIjoiSEFETEVZIFJPQUQiLCJwb3N0YWxDb2RlIjoiQkEyIDVBQSIsImJ1aWxkaW5nTnVtYmVyIjoiOCIsImFkZHJlc3NMb2NhbGl0eSI6IkJBVEgiLCJ2YWxpZEZyb20iOiIyMDAwLTAxLTAxIn1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk1OS0wOC0yMyJ9XX19fQ.MEUCIAD3CkUQctCBxPIonRsYylmAsWsodyzpLlRzSTKvJBxHAiEAsewH-Ke7x8R3879-KQCwGAcYPt_14Wq7a6bvsb5tH_8";
    private final String M1B_DCMAW_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJhdWQiOiJodHRwczovL2lkZW50aXR5LmludGVncmF0aW9uLmFjY291bnQuZ292LnVrIiwibmJmIjoxNjU4ODI5NjQ3LCJpc3MiOiJodHRwczovL3Jldmlldy1wLmludGVncmF0aW9uLmFjY291bnQuZ292LnVrIiwiZXhwIjoxNjU4ODM2ODQ3LCJ2YyI6eyJuYW1lIjpbeyJuYW1lUGFydHMiOlt7InZhbHVlIjoiSm9lIFNobW9lIiwidHlwZSI6IkdpdmVuTmFtZSJ9LHsidmFsdWUiOiJEb2UgVGhlIEJhbGwiLCJ0eXBlIjoiRmFtaWx5TmFtZSJ9XX1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk4NS0wMi0wOCJ9XSwiYWRkcmVzcyI6W3sidXBybiI6IjEwMDIyODEyOTI5Iiwib3JnYW5pc2F0aW9uTmFtZSI6IkZJTkNIIEdST1VQIiwic3ViQnVpbGRpbmdOYW1lIjoiVU5JVCAyQiIsImJ1aWxkaW5nTnVtYmVyICI6IjE2IiwiYnVpbGRpbmdOYW1lIjoiQ09ZIFBPTkQgQlVTSU5FU1MgUEFSSyIsImRlcGVuZGVudFN0cmVldE5hbWUiOiJLSU5HUyBQQVJLIiwic3RyZWV0TmFtZSI6IkJJRyBTVFJFRVQiLCJkb3VibGVEZXBlbmRlbnRBZGRyZXNzTG9jYWxpdHkiOiJTT01FIERJU1RSSUNUIiwiZGVwZW5kZW50QWRkcmVzc0xvY2FsaXR5IjoiTE9ORyBFQVRPTiIsImFkZHJlc3NMb2NhbGl0eSI6IkdSRUFUIE1JU1NFTkRFTiIsInBvc3RhbENvZGUiOiJIUDE2IDBBTCIsImFkZHJlc3NDb3VudHJ5IjoiR0IifV0sImRyaXZpbmdQZXJtaXQiOlt7InBlcnNvbmFsTnVtYmVyIjoiRE9FOTk4MDIwODVKOTlGRyIsImV4cGlyeURhdGUiOiIyMDIzLTAxLTE4IiwiaXNzdWVOdW1iZXIiOm51bGwsImlzc3VlZEJ5IjpudWxsLCJpc3N1ZURhdGUiOm51bGx9XSwiZXZpZGVuY2UiOlt7InR5cGUiOiJJZGVudGl0eUNoZWNrIiwidHhuIjoiZWEyZmVlZmUtNDVhMy00YTI5LTkyM2YtNjA0Y2Q0MDE3ZWMwIiwic3RyZW5ndGhTY29yZSI6MywidmFsaWRpdHlTY29yZSI6MiwiYWN0aXZpdHlIaXN0b3J5U2NvcmUiOiIxIiwiY2hlY2tEZXRhaWxzIjpbeyJjaGVja01ldGhvZCI6InZyaSIsImlkZW50aXR5Q2hlY2tQb2xpY3kiOiJwdWJsaXNoZWQiLCJhY3Rpdml0eUZyb20iOiIyMDE5LTAxLTAxIn0seyJjaGVja01ldGhvZCI6ImJ2ciIsImJpb21ldHJpY1ZlcmlmaWNhdGlvblByb2Nlc3NMZXZlbCI6Mn1dfV19fQ.Ul-eb7s76_F1M5D5maztKdvbrx1_1xGy53_pVZFGmSGJt7niWIe_87ykWm-o1HYaBKYMTvPmSS266ZBZ0t4Gwg";
    private final String M1B_FRAUD_VC =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczovL3Jldmlldy1mLmludGVncmF0aW9uLmFjY291bnQuZ292LnVrIiwic3ViIjoidXJuOnV1aWQ6ZTZlMmUzMjQtNWI2Ni00YWQ2LTgzMzgtODNmOWY4MzdlMzQ1IiwibmJmIjoxNjU4ODI5NzU4LCJleHAiOjE2NTg4MzY5NTgsInZjIjp7ImNyZWRlbnRpYWxTdWJqZWN0Ijp7Im5hbWUiOlt7Im5hbWVQYXJ0cyI6W3sidHlwZSI6IkdpdmVuTmFtZSIsInZhbHVlIjoiS0VOTkVUSCJ9LHsidHlwZSI6IkZhbWlseU5hbWUiLCJ2YWx1ZSI6IkRFQ0VSUVVFSVJBIn1dfV0sImFkZHJlc3MiOlt7ImFkZHJlc3NDb3VudHJ5IjoiR0IiLCJidWlsZGluZ05hbWUiOiIiLCJzdHJlZXROYW1lIjoiSEFETEVZIFJPQUQiLCJwb0JveE51bWJlciI6bnVsbCwicG9zdGFsQ29kZSI6IkJBMiA1QUEiLCJidWlsZGluZ051bWJlciI6IjgiLCJpZCI6bnVsbCwiYWRkcmVzc0xvY2FsaXR5IjoiQkFUSCIsInN1YkJ1aWxkaW5nTmFtZSI6bnVsbH1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk1OS0wOC0yMyJ9XX0sInR5cGUiOlsiVmVyaWZpYWJsZUNyZWRlbnRpYWwiLCJJZGVudGl0eUNoZWNrQ3JlZGVudGlhbCJdLCJldmlkZW5jZSI6W3sidHlwZSI6IklkZW50aXR5Q2hlY2siLCJ0eG4iOiJSQjAwMDEwMzQ5MDA4NyIsImlkZW50aXR5RnJhdWRTY29yZSI6MiwiY2kiOltdfV19fQ.-0NrF3Sv2GwXPEpgAITdE-8SkzuihihEF8inTi_lzo_b-6urQl17pGeWwVyygQRJDbKpxm-Q5ZzXeQuTybLSZQ";
    private final String M1B_FRAUD_VC_WITH_A01 =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczovL3Jldmlldy1mLmludGVncmF0aW9uLmFjY291bnQuZ292LnVrIiwic3ViIjoidXJuOnV1aWQ6ZTZlMmUzMjQtNWI2Ni00YWQ2LTgzMzgtODNmOWY4MzdlMzQ1IiwibmJmIjoxNjU4ODI5NzU4LCJleHAiOjE2NTg4MzY5NTgsInZjIjp7ImNyZWRlbnRpYWxTdWJqZWN0Ijp7Im5hbWUiOlt7Im5hbWVQYXJ0cyI6W3sidHlwZSI6IkdpdmVuTmFtZSIsInZhbHVlIjoiS0VOTkVUSCJ9LHsidHlwZSI6IkZhbWlseU5hbWUiLCJ2YWx1ZSI6IkRFQ0VSUVVFSVJBIn1dfV0sImFkZHJlc3MiOlt7ImFkZHJlc3NDb3VudHJ5IjoiR0IiLCJidWlsZGluZ05hbWUiOiIiLCJzdHJlZXROYW1lIjoiSEFETEVZIFJPQUQiLCJwb0JveE51bWJlciI6bnVsbCwicG9zdGFsQ29kZSI6IkJBMiA1QUEiLCJidWlsZGluZ051bWJlciI6IjgiLCJpZCI6bnVsbCwiYWRkcmVzc0xvY2FsaXR5IjoiQkFUSCIsInN1YkJ1aWxkaW5nTmFtZSI6bnVsbH1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk1OS0wOC0yMyJ9XX0sInR5cGUiOlsiVmVyaWZpYWJsZUNyZWRlbnRpYWwiLCJJZGVudGl0eUNoZWNrQ3JlZGVudGlhbCJdLCJldmlkZW5jZSI6W3sidHlwZSI6IklkZW50aXR5Q2hlY2siLCJ0eG4iOiJSQjAwMDEwMzQ5MDA4NyIsImlkZW50aXR5RnJhdWRTY29yZSI6MiwiY2kiOlsiQTAxIl19XX19.q1QDbqY9rGq6Y3moSFkvx46PjJPsefRoSfdfRB73rqlxTTlIzJO9W6z9tnJzA_ydd6JPnRvJJqdXQMLKK3NwGQ";
    private final String PASSPORT_VC_FAILED =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1cm46dXVpZDpmZDgwMTVmMy1mYjA5LTRiZjctYWU3ZS02MDMzNTAzOGRjYTgiLCJhdWQiOiJodHRwczpcL1wvaWRlbnRpdHkuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJuYmYiOjE2NTg4Mzk3NzIsImlzcyI6Imh0dHBzOlwvXC9yZXZpZXctcC5pbnRlZ3JhdGlvbi5hY2NvdW50Lmdvdi51ayIsImV4cCI6MTY1ODg0Njk3MiwidmMiOnsiZXZpZGVuY2UiOlt7InZhbGlkaXR5U2NvcmUiOjAsInN0cmVuZ3RoU2NvcmUiOjQsImNpIjpbIkQwMiJdLCJ0eG4iOiJlNmMwOTA3NC1hMjczLTRiOGMtOTVmOC0zYWE1YjI2NDgzMmUiLCJ0eXBlIjoiSWRlbnRpdHlDaGVjayJ9XSwiY3JlZGVudGlhbFN1YmplY3QiOnsicGFzc3BvcnQiOlt7ImV4cGlyeURhdGUiOiIyMDI0LTA5LTI5IiwiZG9jdW1lbnROdW1iZXIiOiIxMjM0NTY3ODkifV0sIm5hbWUiOlt7Im5hbWVQYXJ0cyI6W3sidHlwZSI6IkdpdmVuTmFtZSIsInZhbHVlIjoia3NkamhmcyJ9LHsidHlwZSI6IkZhbWlseU5hbWUiLCJ2YWx1ZSI6InFza2RqaGYifV19XSwiYmlydGhEYXRlIjpbeyJ2YWx1ZSI6IjE5ODQtMDktMjgifV19LCJ0eXBlIjpbIlZlcmlmaWFibGVDcmVkZW50aWFsIiwiSWRlbnRpdHlDaGVja0NyZWRlbnRpYWwiXX19.MEUCIQDD0Y0ftHUJmhQ_oDX6wo23loLQ-_EZqoivq2eMoKPYiAIgcmaaGjV7KX8FYNJhPM_v7kWxl1WS9HBx6iiXsv0gODI";
    private final String FRAUD_VC_FAILED =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWYuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDo0MDI1Yjg5Mi1mZmU5LTRjZGUtYWJmMy0xOTgxYTZiYTQ5MmIiLCJuYmYiOjE2NTg4NDAwMTIsImV4cCI6MTY1ODg0NzIxMiwidmMiOnsiY3JlZGVudGlhbFN1YmplY3QiOnsibmFtZSI6W3sibmFtZVBhcnRzIjpbeyJ0eXBlIjoiR2l2ZW5OYW1lIiwidmFsdWUiOiJLRU5ORVRIIn0seyJ0eXBlIjoiRmFtaWx5TmFtZSIsInZhbHVlIjoiREVDRVJRVUVJUkEifV19XSwiYWRkcmVzcyI6W3siYWRkcmVzc0NvdW50cnkiOiJHQiIsImJ1aWxkaW5nTmFtZSI6IiIsInN0cmVldE5hbWUiOiJET1dOSU5HIFNUUkVFVCIsInBvQm94TnVtYmVyIjpudWxsLCJwb3N0YWxDb2RlIjoiU1cxQSAyQUEiLCJidWlsZGluZ051bWJlciI6IjEwIiwiaWQiOm51bGwsImFkZHJlc3NMb2NhbGl0eSI6IkxPTkRPTiIsInN1YkJ1aWxkaW5nTmFtZSI6bnVsbH1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk1OS0wOC0yMyJ9XX0sInR5cGUiOlsiVmVyaWZpYWJsZUNyZWRlbnRpYWwiLCJJZGVudGl0eUNoZWNrQ3JlZGVudGlhbCJdLCJldmlkZW5jZSI6W3sidHlwZSI6IklkZW50aXR5Q2hlY2siLCJ0eG4iOiJSQjAwMDEwMzQ5NzQ0MyIsImlkZW50aXR5RnJhdWRTY29yZSI6MSwiY2kiOlsiQTAyIl19XX19.MEYCIQCRMVowC7JDu1e1jAmNB-isSb4qmlU4lbNJx5bL5n_fjwIhAPf5Yqx-iHzWPeuaevM0D0x3HYVJ-2KFGDyJEo6PwI4g";
    private final String KBV_VC_FAILED =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWsuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDpiOGI0NTZmNy05OGRlLTRkYjktOGJiMy02OWM5ODUxMGY0YWQiLCJuYmYiOjE2NTg5MDkyOTAsImV4cCI6MTY1ODkxNjQ5MCwidmMiOnsidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl0sImV2aWRlbmNlIjpbeyJ0eG4iOiI3TEJINlpETExEIiwidmVyaWZpY2F0aW9uU2NvcmUiOjAsInR5cGUiOiJJZGVudGl0eUNoZWNrIn1dLCJjcmVkZW50aWFsU3ViamVjdCI6eyJuYW1lIjpbeyJuYW1lUGFydHMiOlt7InR5cGUiOiJHaXZlbk5hbWUiLCJ2YWx1ZSI6IktFTk5FVEgifSx7InR5cGUiOiJGYW1pbHlOYW1lIiwidmFsdWUiOiJERUNFUlFVRUlSQSJ9XX1dLCJhZGRyZXNzIjpbeyJhZGRyZXNzQ291bnRyeSI6IkdCIiwiYnVpbGRpbmdOYW1lIjoiIiwic3RyZWV0TmFtZSI6IkhBRExFWSBST0FEIiwicG9zdGFsQ29kZSI6IkJBMiA1QUEiLCJidWlsZGluZ051bWJlciI6IjgiLCJhZGRyZXNzTG9jYWxpdHkiOiJCQVRIIn0seyJhZGRyZXNzQ291bnRyeSI6IkdCIiwidXBybiI6MTAwMTIwMDEyMDc3LCJidWlsZGluZ05hbWUiOiIiLCJzdHJlZXROYW1lIjoiSEFETEVZIFJPQUQiLCJwb3N0YWxDb2RlIjoiQkEyIDVBQSIsImJ1aWxkaW5nTnVtYmVyIjoiOCIsImFkZHJlc3NMb2NhbGl0eSI6IkJBVEgiLCJ2YWxpZEZyb20iOiIyMDAwLTAxLTAxIn1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk1OS0wOC0yMyJ9XX19fQ.MEYCIQCS7vwI_4ILb9opo6ObpuKnLXzSGSGOQhlPOgM1PTI2KwIhAPzpJK4vDC2ztnKK00EPqI8wvbeSMM7TVyku_pm7yRQZ";
    private final String DCMAW_VC_FAILED =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1cm46dXVpZDpzdWJJZGVudGl0eSIsImlzcyI6Imlzc3VlciIsImlhdCI6MTY0NzAxNzk5MCwidmMiOnsiQGNvbnRleHQiOlsiaHR0cHM6Ly93d3cudzMub3JnLzIwMTgvY3JlZGVudGlhbHMvdjEiLCJodHRwczovL3ZvY2FiLmFjY291bnQuZ292LnVrL2NvbnRleHRzL2lkZW50aXR5LXYxLmpzb25sZCJdLCJ0eXBlIjpbIlZlcmlmaWFibGVDcmVkZW50aWFsIiwiSWRlbnRpdHlDaGVja0NyZWRlbnRpYWwiXSwiY3JlZGVudGlhbFN1YmplY3QiOnsibmFtZSI6W3sibmFtZVBhcnRzIjpbeyJ2YWx1ZSI6Ik1PUkdBTiIsInR5cGUiOiJHaXZlbk5hbWUifSx7InZhbHVlIjoiU0FSQUggTUVSRURZVEgiLCJ0eXBlIjoiRmFtaWx5TmFtZSJ9XX1dLCJiaXJ0aERhdGUiOlt7InZhbHVlIjoiMTk3Ni0wMy0xMSJ9XSwiYWRkcmVzcyI6W3sidXBybiI6IjEwMDIyODEyOTI5Iiwib3JnYW5pc2F0aW9uTmFtZSI6IkZJTkNIIEdST1VQIiwic3ViQnVpbGRpbmdOYW1lIjoiVU5JVCAyQiIsImJ1aWxkaW5nTnVtYmVyICI6IjE2IiwiYnVpbGRpbmdOYW1lIjoiQ09ZIFBPTkQgQlVTSU5FU1MgUEFSSyIsImRlcGVuZGVudFN0cmVldE5hbWUiOiJLSU5HUyBQQVJLIiwic3RyZWV0TmFtZSI6IkJJRyBTVFJFRVQiLCJkb3VibGVEZXBlbmRlbnRBZGRyZXNzTG9jYWxpdHkiOiJTT01FIERJU1RSSUNUIiwiZGVwZW5kZW50QWRkcmVzc0xvY2FsaXR5IjoiTE9ORyBFQVRPTiIsImFkZHJlc3NMb2NhbGl0eSI6IkdSRUFUIE1JU1NFTkRFTiIsInBvc3RhbENvZGUiOiJIUDE2IDBBTCIsImFkZHJlc3NDb3VudHJ5IjoiR0IifV0sImRyaXZpbmdQZXJtaXQiOlt7InBlcnNvbmFsTnVtYmVyIjoiTU9SR0E3NTMxMTZTTTlJSiIsImlzc3VlTnVtYmVyIjpudWxsLCJpc3N1ZWRCeSI6bnVsbCwiaXNzdWVEYXRlIjpudWxsLCJleHBpcnlEYXRlIjoiMjAyMy0wMS0xOCJ9XX0sImV2aWRlbmNlIjpbeyJ0eXBlIjoiSWRlbnRpdHlDaGVjayIsInR4biI6ImJjZDIzNDYiLCJzdHJlbmd0aFNjb3JlIjozLCJ2YWxpZGl0eVNjb3JlIjowLCJhY3Rpdml0eUhpc3RvcnlTY29yZSI6IjEiLCJjaSI6W10sImZhaWxlZENoZWNrRGV0YWlscyI6W3siY2hlY2tNZXRob2QiOiJ2cmkiLCJpZGVudGl0eUNoZWNrUG9saWN5IjoicHVibGlzaGVkIiwiYWN0aXZpdHlGcm9tIjoiMjAxOS0wMS0wMSJ9LHsiY2hlY2tNZXRob2QiOiJidnIiLCJiaW9tZXRyaWNWZXJpZmljYXRpb25Qcm9jZXNzTGV2ZWwiOjJ9XX1dfX0.E8xSlxvlzXkk21ro7TKipDwqSk32hmGrh4xHBeo-GEydWqdXUnSaRJOtLp7QLMAownm-8d0SbDq7_Vuin9_rvA";
    private final String VC_WITH_BAD_EVIDENCE_BLOB =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJodHRwczpcL1wvcmV2aWV3LWYuaW50ZWdyYXRpb24uYWNjb3VudC5nb3YudWsiLCJzdWIiOiJ1cm46dXVpZDplNmUyZTMyNC01YjY2LTRhZDYtODMzOC04M2Y5ZjgzN2UzNDUiLCJuYmYiOjE2NTg4Mjk3NTgsImV4cCI6MTY1ODgzNjk1OCwidmMiOnsiY3JlZGVudGlhbFN1YmplY3QiOnsibmFtZSI6W3sibmFtZVBhcnRzIjpbeyJ0eXBlIjoiR2l2ZW5OYW1lIiwidmFsdWUiOiJLRU5ORVRIIn0seyJ0eXBlIjoiRmFtaWx5TmFtZSIsInZhbHVlIjoiREVDRVJRVUVJUkEifV19XSwiYWRkcmVzcyI6W3siYWRkcmVzc0NvdW50cnkiOiJHQiIsImJ1aWxkaW5nTmFtZSI6IiIsInN0cmVldE5hbWUiOiJIQURMRVkgUk9BRCIsInBvQm94TnVtYmVyIjpudWxsLCJwb3N0YWxDb2RlIjoiQkEyIDVBQSIsImJ1aWxkaW5nTnVtYmVyIjoiOCIsImlkIjpudWxsLCJhZGRyZXNzTG9jYWxpdHkiOiJCQVRIIiwic3ViQnVpbGRpbmdOYW1lIjpudWxsfV0sImJpcnRoRGF0ZSI6W3sidmFsdWUiOiIxOTU5LTA4LTIzIn1dfSwidHlwZSI6WyJWZXJpZmlhYmxlQ3JlZGVudGlhbCIsIklkZW50aXR5Q2hlY2tDcmVkZW50aWFsIl0sImV2aWRlbmNlIjpbeyJ0eXBlIjoiSWRlbnRpdHlDaGVjayIsInR4biI6IlJCMDAwMTAzNDkwMDg3IiwidGhpc0RvZXNOb3RNYWtlU2Vuc2VTY29yZSI6MSwiY2kiOltdfV19fQo.MEUCIHoe7TsSTTORaj2X5cpv7Fpg1gVenFwEhYL4tf6zt3eJAiEAiwqUTOROjTB-Gyxt-IEwUQNndj_L43dMAnrPRaWnzNE";

    @Test
    void credentialsSatisfyProfileShouldReturnTrueIfCredentialsSatisfyProfile() throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, M1A_FRAUD_VC, M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnTrueIfCredentialsM1BSatisfyProfile()
            throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1B_DCMAW_VC, M1A_ADDRESS_VC, M1B_FRAUD_VC), Gpg45Profile.M1B));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnTrueIfCredentialsSatisfyProfileAndOnlyA01CI()
            throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, M1A_FRAUD_VC_WITH_A01, M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void
            credentialsSatisfyProfileShouldReturnTrueIfCredentialsSatisfyProfileAndOnlyA01CIForTheM1BProfile()
                    throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1B_DCMAW_VC, M1A_ADDRESS_VC, M1B_FRAUD_VC_WITH_A01),
                        Gpg45Profile.M1B));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfNoCredentialsFound() throws Exception {
        assertFalse(evaluator.credentialsSatisfyProfile(List.of(), Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfOnlyPassportCredential() throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(List.of(M1A_PASSPORT_VC), Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfOnlyOnlyPassportAndAddressCredential()
            throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC), Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfOnlyOnlyPassportAndAddressAndFraudCredential()
            throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, M1A_FRAUD_VC), Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfOnlyOnlyAppCredential() throws Exception {
        assertFalse(evaluator.credentialsSatisfyProfile(List.of(M1B_DCMAW_VC), Gpg45Profile.M1B));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfFailedPassportCredential() throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(PASSPORT_VC_FAILED, M1A_ADDRESS_VC, M1A_FRAUD_VC, M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfFailedFraudCredential() throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, FRAUD_VC_FAILED, M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfFailedKbvCredential() throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, M1A_FRAUD_VC, KBV_VC_FAILED),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldReturnFalseIfFailedAppCredential() throws Exception {
        assertFalse(
                evaluator.credentialsSatisfyProfile(
                        List.of(DCMAW_VC_FAILED, M1A_ADDRESS_VC, M1B_FRAUD_VC), Gpg45Profile.M1B));
    }

    @Test
    void credentialsSatisfyProfileShouldUseHighestScoringValuesForCredentials() throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(
                                M1A_PASSPORT_VC,
                                M1A_ADDRESS_VC,
                                FRAUD_VC_FAILED,
                                M1A_FRAUD_VC,
                                KBV_VC_FAILED,
                                M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldUseHighestScoringValuesForCredentialsIncludingFailedAppVC()
            throws Exception {
        assertTrue(
                evaluator.credentialsSatisfyProfile(
                        List.of(
                                DCMAW_VC_FAILED,
                                M1A_PASSPORT_VC,
                                M1A_ADDRESS_VC,
                                FRAUD_VC_FAILED,
                                M1A_FRAUD_VC,
                                KBV_VC_FAILED,
                                M1A_KBV_VC),
                        Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldThrowIfCredentialsCanNotBeParsed() {
        assertThrows(
                ParseException.class,
                () ->
                        evaluator.credentialsSatisfyProfile(
                                List.of("This is nonsense 🫤"), Gpg45Profile.M1A));
    }

    @Test
    void credentialsSatisfyProfileShouldThrowIfUnrecognisedEvidenceType() {
        assertThrows(
                UnknownEvidenceTypeException.class,
                () ->
                        evaluator.credentialsSatisfyProfile(
                                List.of(VC_WITH_BAD_EVIDENCE_BLOB), Gpg45Profile.M1A));
    }

    @Test
    void anyCredentialsGatheredDoNotMeetM1AShouldReturnFalseForGoodCredentials() throws Exception {
        List<String> goodCredentials =
                List.of(M1A_PASSPORT_VC, M1A_ADDRESS_VC, M1A_FRAUD_VC, M1A_KBV_VC);
        List<String> gatheredCredentials = new ArrayList<>();
        assertEquals(
                Optional.empty(),
                evaluator.getJourneyResponseIfAnyCredsFailM1A(gatheredCredentials));
        for (String credential : goodCredentials) {
            gatheredCredentials.add(credential);
            assertEquals(
                    Optional.empty(),
                    evaluator.getJourneyResponseIfAnyCredsFailM1A(gatheredCredentials));
        }
    }

    @Test
    void getFailedJourneyResponseShouldReturnEmptyOptionalForFraudCredentialWithOnlyA01()
            throws Exception {
        assertEquals(
                Optional.empty(),
                evaluator.getJourneyResponseIfAnyCredsFailM1A(List.of(M1A_FRAUD_VC_WITH_A01)));
    }

    @Test
    void getFailedJourneyResponseShouldReturnPyiNoMatchJourneyResponseForBadPassportCredential()
            throws Exception {
        Optional<JourneyResponse> journeyResponse =
                Optional.of(new JourneyResponse("/journey/pyi-no-match"));
        assertEquals(
                journeyResponse,
                evaluator.getJourneyResponseIfAnyCredsFailM1A(List.of(PASSPORT_VC_FAILED)));
    }

    @Test
    void getFailedJourneyResponseShouldReturnPyiNoMatchJourneyResponseForBadFraudCredential()
            throws Exception {
        Optional<JourneyResponse> journeyResponse =
                Optional.of(new JourneyResponse("/journey/pyi-no-match"));
        assertEquals(
                journeyResponse,
                evaluator.getJourneyResponseIfAnyCredsFailM1A(List.of(FRAUD_VC_FAILED)));
    }

    @Test
    void getFailedJourneyResponseShouldReturnPyiKbvFailJourneyResponseForBadKbvCredential()
            throws Exception {
        Optional<JourneyResponse> journeyResponse =
                Optional.of(new JourneyResponse("/journey/pyi-kbv-fail"));
        assertEquals(
                journeyResponse,
                evaluator.getJourneyResponseIfAnyCredsFailM1A(List.of(KBV_VC_FAILED)));
    }
}
