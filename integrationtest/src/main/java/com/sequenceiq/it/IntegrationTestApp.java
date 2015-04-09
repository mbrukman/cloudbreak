package com.sequenceiq.it;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.testng.TestNG;
import org.testng.internal.Yaml;
import org.testng.xml.XmlSuite;

@SpringBootApplication
public class IntegrationTestApp implements CommandLineRunner {
    @Value("${integrationtest.testsuite.threadpool.size:3}")
    private int suiteThreadPoolSize;

    @Autowired
    private TestNG testng;

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public void run(String... args) throws Exception {
        testng.setSuiteThreadPoolSize(suiteThreadPoolSize);
//        testng.setTestSuites(Arrays.asList(args));
        testng.setXmlSuites(loadSuites(args));
        testng.setVerbose(2);
        testng.run();
    }

    private List<XmlSuite> loadSuites(String... suitePathes) throws Exception {
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
