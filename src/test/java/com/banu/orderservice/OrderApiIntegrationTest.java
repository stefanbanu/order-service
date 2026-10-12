package com.banu.orderservice;

import static org.assertj.core.api.Assertions.assertThat;
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

    private String createOrderJson(String body) throws Exception {
        return mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createOrder(String body) throws Exception {
        // Typed local, not String.valueOf(JsonPath.read(...)): read() is generic,
        // so T would infer as char[] to match a String.valueOf overload and blow up.
        Number id = JsonPath.read(createOrderJson(body), "$.id");
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

    /**
     * Deliberately patches to PAID rather than CANCELLED: a PATCH to CANCELLED
     * delegates to the cancellation path, which legitimately stamps cancelledAt,
     * so it is no longer the right target for a "nothing else moved" assertion.
     * The CANCELLED side of that is
     * {@link #patchToCancelledStampsCancelledAtAndLeavesTheRestAlone}.
     */
    @Test
    void persistsTheNewStatusWithoutTouchingTheOtherFields() throws Exception {
        String created = createOrderJson("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        Number idValue = JsonPath.read(created, "$.id");
        String id = idValue.toString();
        Object createdAt = JsonPath.read(created, "$.createdAt");
        assertThat(createdAt).isNotNull();

        patchStatus(id, "PAID").andExpect(status().isOk());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.id").value(Integer.parseInt(id)))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());
    }

    /**
     * A PATCH to CANCELLED goes through OrderService.cancel, so it stamps
     * cancelledAt as well as the status. It carries no reason, which is what
     * keeps cancellationReason null here while POST /orders/{id}/cancel can set
     * it. Everything else about the order still has to survive untouched; the
     * refund half of this lives in OrderCancelIntegrationTest, where the payment
     * port is mocked.
     */
    @Test
    void patchToCancelledStampsCancelledAtAndLeavesTheRestAlone() throws Exception {
        String created = createOrderJson("{\"customerEmail\":\"bob@example.com\",\"amount\":42.50}");
        Number idValue = JsonPath.read(created, "$.id");
        String id = idValue.toString();
        Object createdAt = JsonPath.read(created, "$.createdAt");
        assertThat(createdAt).isNotNull();

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.cancellationReason").isEmpty());

        mockMvc.perform(get("/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andExpect(jsonPath("$.cancellationReason").isEmpty())
                .andExpect(jsonPath("$.customerEmail").value("bob@example.com"))
                .andExpect(jsonPath("$.amount").value(42.50))
                .andExpect(jsonPath("$.id").value(Integer.parseInt(id)))
                .andExpect(jsonPath("$.createdAt").value(createdAt));
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
    void repeatedPatchToTheSameStatusIsAnIdempotent200() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        patchStatus(id, "PAID").andExpect(status().isOk());

        patchStatus(id, "PAID")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("PAID"));
    }

    /**
     * The repeat patch reaches OrderService.cancel, whose already-CANCELLED early
     * return is what keeps this a 200 with no second write: cancelledAt has to
     * come back byte for byte, not be re-stamped.
     */
    @Test
    void patchToTheCurrentStatusIsIdempotentEvenWhenTerminal() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        String first = patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Object firstCancelledAt = JsonPath.read(first, "$.cancelledAt");

        patchStatus(id, "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value(firstCancelledAt))
                .andExpect(jsonPath("$.cancellationReason").isEmpty());
    }

    @Test
    void patchToTheStartingStatusIsIdempotentWithoutAdvancingIt() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");

        patchStatus(id, "NEW")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void aTerminalStatusStillRefusesADifferentTarget() throws Exception {
        String id = createOrder("{\"customerEmail\":\"bob@example.com\",\"amount\":10.00}");
        patchStatus(id, "PAID").andExpect(status().isOk());
        patchStatus(id, "SHIPPED").andExpect(status().isOk());

        patchStatus(id, "CANCELLED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Order " + id + " is SHIPPED, which is final; it cannot become CANCELLED"));
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
