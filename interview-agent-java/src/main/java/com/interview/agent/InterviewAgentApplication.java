/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication
public class InterviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(InterviewAgentApplication.class);
        // eval 子命令为离线 CLI（由 EvalCommandRunner 处理后自行退出）：
        // 用随机端口启动，避免与正在运行的 Web 服务抢占 9090 端口。
        if (args.length > 0 && "eval".equals(args[0])) {
            app.setDefaultProperties(Map.of("server.port", "0"));
        }
        app.run(args);
    }
}
