package org.apache.shenyu.register.instance.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.NewService;
import org.apache.shenyu.register.common.dto.InstanceRegisterDTO;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class ConsulInstanceRegisterRepositoryTest {

    private final Map<String, String> registerInstanceMap = new HashMap<>();

    private final ConsulInstanceRegisterRepository consulRepository
            = new ConsulInstanceRegisterRepository();

    @Before
    public void setup() throws Exception {
        final Field field = consulRepository.getClass().getDeclaredField("consulClient");
        field.setAccessible(true);
        field.set(consulRepository, mockConsulClient());
    }

    private ConsulClient mockConsulClient() {
        final ConsulClient consulClient = mock(ConsulClient.class);
        doAnswer(invocation -> {
            final NewService newService = invocation.getArgument(0);
            registerInstanceMap.put(newService.getName(), newService.getId());
            return mock(Response.class);
        }).when(consulClient).agentServiceRegister(any());
        return consulClient;
    }

    @Test
    public void testPersistInstance() {
        InstanceRegisterDTO data = InstanceRegisterDTO.builder()
                .appName("shenyu-test")
                .host("shenyu-host")
                .port(9195)
                .build();
        consulRepository.persistInstance(data);
        final String registerName = ConsulInstanceRegisterRepository.SHENYU_REGISTER;
        // registerName is "shenyu-register-instance"
        assertTrue(registerInstanceMap.containsKey(registerName));
        assertEquals(registerInstanceMap.get(registerName), "shenyu-host:9195");
        consulRepository.close();
    }
}

