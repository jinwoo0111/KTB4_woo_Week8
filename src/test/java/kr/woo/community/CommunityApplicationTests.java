package kr.woo.community;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("test")
class CommunityApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebEndpointsSupplier webEndpointsSupplier;

	@Test
	void contextLoads() {
	}

	@Test
	void healthEndpointReturnsOnlyOverallStatus() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.groups").doesNotExist())
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	void onlyHealthActuatorEndpointIsExposed() {
		Set<String> exposedEndpointIds = webEndpointsSupplier.getEndpoints()
				.stream()
				.map(endpoint -> endpoint.getEndpointId().toString())
				.collect(Collectors.toSet());

		assertEquals(Set.of("health"), exposedEndpointIds);
	}

	@Test
	void healthEndpointDoesNotAllowPostRequestsWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/actuator/health"))
				.andExpect(status().isUnauthorized());
	}

}
