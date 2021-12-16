package org.apache.shenyu.register.instance.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import org.apache.shenyu.common.config.ShenyuConfig;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.register.common.dto.InstanceRegisterDTO;
import org.apache.shenyu.register.instance.api.ShenyuInstanceRegisterRepository;
import org.apache.shenyu.spi.Join;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

@Join
public class ConsulInstanceRegisterRepository implements ShenyuInstanceRegisterRepository {

    public static final String SHENYU_REGISTER = "shenyu-register-instance";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulInstanceRegisterRepository.class);

    private ConsulClient consulClient;

    private String checkerInterval;

    private String checkerTimeout;

    @Override
    public void init(final ShenyuConfig.InstanceConfig config) {
        final Properties props = config.getProps();
        this.checkerInterval = props.getProperty("checkerInterval", "5s");
        this.checkerTimeout = props.getProperty("checkerTimeout", "1s");
        this.consulClient = new ConsulClient(config.getServerLists());
        LOGGER.info("init:{}, serverList: {}, checkInterval:{}, checkTimeout:{}",
                config, config.getServerLists(),
                checkerInterval, checkerTimeout);
    }

    @Override
    public void persistInstance(final InstanceRegisterDTO instance) {
        final String instanceName = String.join(Constants.COLONS, instance.getHost(), String.valueOf(instance.getPort()));
        final NewService service = new NewService();
        service.setId(instanceName);
        service.setName(SHENYU_REGISTER);
        service.setAddress(instance.getHost());
        service.setPort(instance.getPort());
        // add health check for register instance.
        final NewService.Check check = new NewService.Check();
        check.setTimeout(checkerTimeout);
        check.setInterval(checkerInterval);
        check.setHttp(buildHttpHealthCheckAddress(instance));
        service.setCheck(check);
        // register instance
        this.consulClient.agentServiceRegister(service);
        LOGGER.info("persistInstance:{}", instance);
    }

    private static String buildHttpHealthCheckAddress(final InstanceRegisterDTO instance) {
        return "http://" + instance.getHost() + ":" + instance.getPort() + "/health_check";
    }
}
