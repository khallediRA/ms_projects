package com.example.accounts.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.example.accounts.model.Accounts;
import com.example.accounts.model.AccountsServiceConfig;
import com.example.accounts.model.Card;
import com.example.accounts.model.Customer;
import com.example.accounts.model.CustomerDetails;
import com.example.accounts.model.Loan;
import com.example.accounts.model.Properties;
import com.example.accounts.model.Transaction;
import com.example.accounts.repository.AccountsRepository;
import com.example.accounts.service.client.CardsFeignClient;
import com.example.accounts.service.client.LoansFeignClient;
import com.example.accounts.service.client.TransactionsFeignClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;

@RestController
public class AccountsController {
    @Autowired
    private AccountsRepository accountsRepository;

    @Autowired
    private AccountsServiceConfig accountsConfig;

    @Autowired
    private LoansFeignClient loansFeignClient;

    @Autowired
    private CardsFeignClient cardsFeignClient;

    @Autowired
    private TransactionsFeignClient transactionsFeignClient;

    @Value("${nodejs.server.url}")
    private String nodeJSUrl;

    private static final Logger Logger = LoggerFactory.getLogger(AccountsController.class);

    @PostMapping("/myAccount")
    @Timed(value = "getAccountDetails.time", description = "Time taken to return account details")
    public Accounts getAccountDetails(@RequestBody Customer customer) {
        Accounts accounts = accountsRepository.findByCustomerId(customer.getCustomerId());
        if (accounts != null) {
            return accounts;
        }
        return null;
    }

    @RequestMapping(value = "/account/properties", method = RequestMethod.GET, produces = "application/json")
    public String getPropertyDetails() throws JsonProcessingException {
        ObjectWriter ow = new ObjectMapper().writer()
                .withDefaultPrettyPrinter();
        Properties properties = new Properties(accountsConfig.getMsg(), accountsConfig.getBuildVersion(),
                accountsConfig.getMailDetails(), accountsConfig.getActiveBranches());
        String jsonStr = ow.writeValueAsString(properties);
        return jsonStr;

    }

    @PostMapping("/myCustomerDetails")
    // @CircuitBreaker(name = "detailsForCustomerSupportApp", fallbackMethod =
    // "myCustomerDetailsFallBack")
    @Retry(name = "retryForCustomerDetails", fallbackMethod = "myCustomerDetailsFallBack")
    public CustomerDetails myCustomerDetails(@RequestHeader("eazybank-correlation-id") String correlationId,
            @RequestBody Customer customer) {
        Logger.info("myCustomerDetails() method started");
        Accounts accounts = accountsRepository.findByCustomerId(customer.getCustomerId());
        List<Loan> loans = loansFeignClient.getAllLoans(correlationId, customer);
        List<Card> cards = cardsFeignClient.getCardDetails(correlationId, customer);
        List<Transaction> transactions = transactionsFeignClient.getAllTransactions(correlationId, customer);
        CustomerDetails customerDetails = new CustomerDetails();
        customerDetails.setAccounts(accounts);
        customerDetails.setCards(cards);
        customerDetails.setLoans(loans);
        customerDetails.setTransactions(transactions);
        ;
        Logger.info("myCustomerDetails() method ended");

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestBody = "{\"to\": \"rached.khalledi@esprit.tn\", \"subject\": \"Test Email\", \"text\": \"Hello, this is a test email from the Notification Service.\"}";

        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(nodeJSUrl, HttpMethod.POST, requestEntity,
                String.class);
        String responseBody = response.getBody();

        // Process the response from the Node.js server
        System.out.println("Response from Node.js server: " + responseBody);

        return customerDetails;
    }

    private CustomerDetails myCustomerDetailsFallBack(
            @RequestHeader("eazybank-correlation-id") String correlationId, Customer customer, Throwable t) {
        Accounts accounts = accountsRepository.findByCustomerId(customer.getCustomerId());
        List<Loan> loans = loansFeignClient.getAllLoans(correlationId, customer);
        CustomerDetails customerDetails = new CustomerDetails();
        customerDetails.setAccounts(accounts);
        customerDetails.setLoans(loans);
        return customerDetails;
    }

    @GetMapping("/sayHello")
    @RateLimiter(name = "sayHello", fallbackMethod = "sayHelloFallback")
    public String sayHello() {
        return "Hello, Welcome from k8s cluster";
    }

    private String sayHelloFallback(Throwable t) {
        return "Hi, Welcome from fallback";
    }
}
