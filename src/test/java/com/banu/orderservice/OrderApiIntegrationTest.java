package com.banu.orderservice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only test that drives controller -> service -> Hibernate -> H2 together,
 * so it catches mapping and schema faults the mocked web slice cannot.
 * Transactional so each test rolls back and list assertions stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String createOrder(String body) throws Exception {
        String json = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Typed local, not String.valueOf(JsonPath.read(...)): read() is generic,
        // so T would infer as char[] to match a String.valueOf overload and blow up.
        Number id = JsonPath.read(json, "$.id");
        return id.toString();
    }

    private ResultActions patchStatus(String id, String status) throws Exception {
        return mockMvc.perform(patch("/orders/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    @Test
    void roundTripsAnOrderThroughTheRealStack() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void statusIsServerSetAndIgnoresClientSuppliedValue() throws Exception {
        String id = createOrder(
                "{\"customerEmail\":\"bob@example.com\",\"amount\":10.00,\"status\":\"PAID\"}");

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void idIsServerSetAndIgnoresClientSuppliedValue() throws Exception {
        String id = createOrder(
                "{\"id\":9999,\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");

        mockMvc.perform(get("/orders/9999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/" + id)).andExpect(status().isOk());
    }

    @Test
    void listsByEmailCaseInsensitively() throws Exception {
        createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");

        mockMvc.perform(get("/orders").param("email", "BOB@EXAMPLE.COM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerEmail").value("bob@example.com"));
    }

    @Test
    void listsAllWhenEmailOmitted() throws Exception {
        createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        createOrder("{\"customerEmail\":\"alice@example.com\",\"amount\":20.00}");

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/orders/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void rejectsInvalidInputBeforeTouchingTheDatabase() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerEmail\":\"not-an-email\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerEmail").exists())
                .andExpect(jsonPath("$.errors.amount").exists());

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void walksTheHappyPathNewToPaidToShipped() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        patchStatus(id, "PAID")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        patchStatus(id, "SHIPPED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void persistsTheNewStatusWithoutTouchingTheOtherFields() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");

        patchStatus(id, "CANCELLED").andExpect(status().isOk());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.id").value(Integer.parseInt(id)));
    }

    @Test
    void refuses409OnASkippedStepAndLeavesTheStatusAlone() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");

        patchStatus(id, "SHIPPED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Invalid status transition"));

        mockMvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void refuses409OnMovingOutOfATerminalStatus() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        patchStatus(id, "CANCELLED").andExpect(status().isOk());

        patchStatus(id, "PAID").andExpect(status().isConflict());

        mockMvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void refuses409OnARepeatedPatchSoRetriesAreNotSilentlyAccepted() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        patchStatus(id, "PAID").andExpect(status().isOk());

        patchStatus(id, "PAID").andExpect(status().isConflict());
    }

    @Test
    void returns404WhenPatchingAnUnknownId() throws Exception {
        patchStatus("9999", "PAID")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void returns400WithAProblemDetailForAnUnknownStatusName() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");

        patchStatus(id, "BOGUS")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Malformed request"));

        mockMvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("NEW"));
    }
}
