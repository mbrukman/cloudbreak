package com.sequenceiq.it;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.util.CollectionUtils;
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
        setupSuites(testng);
        testng.setVerbose(2);
        testng.run();
    }

    private void setupSuites(TestNG testng) throws Exception {
        List<String> testTypes = itProps.getTestTypes();
        List<String> suiteFiles = itProps.getSuiteFiles();
        if (!CollectionUtils.isEmpty(testTypes)) {
            Set<String> suitePathes = new LinkedHashSet<>();
            for (String testType : testTypes) {
                List<String> suites = itProps.getTestSuites(testType);
                if (suites != null) {
                    suitePathes.addAll(suites);
                }
            }
            testng.setXmlSuites(loadSuites(suitePathes));
        } else if (!CollectionUtils.isEmpty(suiteFiles)) {
            testng.setTestSuites(suiteFiles);
        }
    }

    private List<XmlSuite> loadSuites(Collection<String> suitePathes) throws Exception {
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
