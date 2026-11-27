package com.insurai.platform.fraudengine.unit;

import com.insurai.platform.fraudengine.api.ReportController;
import com.insurai.platform.fraudengine.application.ReportService;
import com.insurai.platform.fraudengine.domain.model.FraudReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
// The platform's security chain is covered by the platform tests; these slices exercise the controller and the
// fraud engine's own X-Admin-Token interceptor, so servlet filters (Spring Security) are switched off here.
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

    private static final String TOKEN = "local-dev-admin-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportService reportService;

    @Test
    void trigger_with_valid_token_is_accepted() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reports/trigger").header("X-Admin-Token", TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("TRIGGERED"));
    }

    @Test
    void trigger_without_token_is_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reports/trigger"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void existing_report_is_returned() throws Exception {
        when(reportService.getReport(LocalDate.parse("2026-05-30"))).thenReturn(Optional.of(
                new FraudReport(LocalDate.parse("2026-05-30"), 100, 5,
                        new BigDecimal("5.00"), new BigDecimal("12.30"), List.of())));

        mockMvc.perform(get("/api/v1/reports").param("date", "2026-05-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(100))
                .andExpect(jsonPath("$.flaggedCount").value(5));
    }

    @Test
    void missing_report_returns_404() throws Exception {
        when(reportService.getReport(LocalDate.parse("2026-05-29"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/reports").param("date", "2026-05-29"))
                .andExpect(status().isNotFound());
    }
}
