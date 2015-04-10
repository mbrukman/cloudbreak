package com.sequenceiq.it;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.testng.TestNG;
import org.testng.internal.Yaml;
import org.testng.xml.XmlSuite;

import com.sequenceiq.it.cloudbreak.config.ITProps;

@SpringBootApplication
@EnableConfigurationProperties(ITProps.class)
public class IntegrationTestApp implements CommandLineRunner {
    @Value("${integrationtest.testsuite.threadPoolSize}")
    private int suiteThreadPoolSize;

    @Autowired
    private TestNG testng;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ITProps itProps;

    @Override
    public void run(String... args) throws Exception {
        testng.setSuiteThreadPoolSize(suiteThreadPoolSize);
        setupSuites(testng, args);
        testng.setVerbose(2);
        testng.run();
    }

    private void setupSuites(TestNG testng, String... args) throws Exception {
        List<String> suitePathes = itProps.getTestSuites(args[0]);
        if (suitePathes != null) {
            testng.setXmlSuites(loadSuites(suitePathes));
        } else {
            testng.setTestSuites(Arrays.asList(args));
        }
    }

    private List<XmlSuite> loadSuites(List<String> suitePathes) throws Exception {
        List<XmlSuite> suites = new ArrayList<>();
        for (String suitePath: suitePathes) {
            suites.add(loadSuite(suitePath));
        }
        return suites;
    }

    private XmlSuite loadSuite(String suitePath) throws Exception {
        return Yaml.parse(suitePath, applicationContext.getResource(suitePath).getInputStream());
    }

    public static void main(String[] args) throws Exception {
        SpringApplication.run(IntegrationTestApp.class, args);
    }
}
