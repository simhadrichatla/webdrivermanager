/*
 * (C) Copyright 2023 Boni Garcia (https://bonigarcia.github.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.github.bonigarcia.wdm.test.docker;

import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.events.ConsoleEvent;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback.Adapter;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder;

@TestInstance(PER_CLASS)
class DockerManualTest {

    static final Logger log = getLogger(lookup().lookupClass());

    WebDriver driver;
    DevTools devTools;
    DockerClient dockerClient;
    String containerId;

    String imageId = "selenoid/vnc:chrome_118.0";
//    String imageId = "selenium/standalone-chrome:latest";
    String dockerHost = "localhost";
    int dockerPort = 4444;
    String remoteUrl = String.format("http://%s:%s/", dockerHost,
            dockerPort + 1);

    @BeforeAll
    void setupClass() throws Exception {
        // Docker client
        log.debug("Docker client");
        DefaultDockerClientConfig.Builder dockerClientConfigBuilder = DefaultDockerClientConfig
                .createDefaultConfigBuilder();
        DockerClientConfig dockerClientConfig = dockerClientConfigBuilder
                .build();
        ApacheDockerHttpClient dockerHttpClient = new Builder()
                .dockerHost(dockerClientConfig.getDockerHost()).build();
        dockerClient = DockerClientBuilder.getInstance()
                .withDockerHttpClient(dockerHttpClient).build();

        // Pull image
        log.debug("Pull image");
        dockerClient.pullImageCmd(imageId)
                .exec(new Adapter<PullResponseItem>() {
                }).awaitCompletion();

        // Start container
        log.debug("Start container");
        HostConfig hostConfigBuilder = new HostConfig();
        try (CreateContainerCmd containerConfigBuilder = dockerClient
                .createContainerCmd(imageId)) {
            hostConfigBuilder.withNetworkMode("host");

            ExposedPort exposedPort = new ExposedPort(dockerPort,
                    InternetProtocol.TCP);
            Binding binding = new Binding(dockerHost,
                    String.valueOf(dockerPort + 1));
            PortBinding portBinding = new PortBinding(binding, exposedPort);

            containerConfigBuilder.withExposedPorts(exposedPort);
            hostConfigBuilder.withPortBindings(portBinding);

            containerId = containerConfigBuilder
                    .withHostConfig(hostConfigBuilder).exec().getId();

            dockerClient.startContainerCmd(containerId).exec();

            // Manual wait
            System.out.println("Manual wait");
            Thread.sleep(5000);
        }
    }

    @BeforeEach
    void setupTest() throws Exception {
        ChromeOptions options = new ChromeOptions();
        WebDriver remoteDriver = new RemoteWebDriver(new URL(remoteUrl),
                options);

        driver = new Augmenter().augment(remoteDriver);
        devTools = ((HasDevTools) driver).getDevTools();
        devTools.createSession();
    }

    @Test
    void test() throws Exception {
        CompletableFuture<ConsoleEvent> futureEvents = new CompletableFuture<>();
        devTools.getDomains().events()
                .addConsoleListener(futureEvents::complete);

        CompletableFuture<JavascriptException> futureJsExc = new CompletableFuture<>();
        devTools.getDomains().events()
                .addJavascriptExceptionListener(futureJsExc::complete);

        driver.get(
                "https://bonigarcia.dev/selenium-webdriver-java/console-logs.html");

        ConsoleEvent consoleEvent = futureEvents.get(5, TimeUnit.SECONDS);
        log.debug("ConsoleEvent: {} {} {}", consoleEvent.getTimestamp(),
                consoleEvent.getType(), consoleEvent.getMessages());

        JavascriptException jsException = futureJsExc.get(5, TimeUnit.SECONDS);
        log.debug("JavascriptException: {} {}", jsException.getMessage(),
                jsException.getSystemInformation());
    }

    @AfterEach
    void teardown() {
        devTools.close();
        driver.quit();
    }

    @AfterAll
    void teardownClass() throws Exception {
        // Stop container
        log.debug("Stop container");
        dockerClient.killContainerCmd(containerId).exec();
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
    }

}