package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Component
public class KafkaTransactionListener {

    private final DatabaseConduit databaseConduit;
    private final RestTemplate restTemplate;

    public KafkaTransactionListener(DatabaseConduit databaseConduit, RestTemplate restTemplate) {
        this.databaseConduit = databaseConduit;
        this.restTemplate = restTemplate;
    }

    @KafkaListener(topics = "${general.kafka-topic}", groupId = "midas-group")
    public void receiveTransaction(Transaction transaction) {

        Long senderId = transaction.getSenderId();
        Long recipientId = transaction.getRecipientId();
        float amount = transaction.getAmount();

        Optional<UserRecord> senderOptional = databaseConduit.findById(senderId);
        Optional<UserRecord> recipientOptional = databaseConduit.findById(recipientId);

        // stop if sender or recipient does not exist
        if (senderOptional.isEmpty() || recipientOptional.isEmpty()) {
            return;
        }

        UserRecord sender = senderOptional.get();
        UserRecord recipient = recipientOptional.get();

        // stop if sender does not have enough money
        if (sender.getBalance() < amount) {
            return;
        }

        // call the incentive API
        Incentive incentiveResponse =
                restTemplate.postForObject(
                        "http://localhost:8080/incentive",
                        transaction,
                        Incentive.class
                );

        float incentiveAmount = 0;

        if (incentiveResponse != null) {
            incentiveAmount = incentiveResponse.getAmount();
        }

        // update balances
        sender.setBalance(sender.getBalance() - amount);
        recipient.setBalance(recipient.getBalance() + amount + incentiveAmount);

        if ("wilbur".equals(sender.getName())) {
            System.out.println("wilbur balance now: " + sender.getBalance());
        }
        if ("wilbur".equals(recipient.getName())) {
            System.out.println("wilbur balance now: " + recipient.getBalance());
        }

        // save updated users
        databaseConduit.save(sender);
        databaseConduit.save(recipient);

        // save transaction record with incentive
        TransactionRecord transactionRecord =
                new TransactionRecord(sender, recipient, amount, incentiveAmount);

        databaseConduit.saveTransaction(transactionRecord);
    }
}