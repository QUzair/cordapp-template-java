package com.template.flows;

import com.template.states.IOUState;
import com.template.contracts.IOUContract;
import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.time.LocalDate;
import net.corda.finance.workflows.asset.CashUtils;
import net.corda.finance.contracts.asset.PartyAndAmount;
import net.corda.core.contracts.StateAndRef;

public class IssueFlow {

    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends IOUBaseFlow {

        public Initiator(Amount<Currency> amount, Party borrower, String externalId, Party lender) {
            this.amount = amount;
            this.borrower = borrower;
            this.externalId = externalId;
            this.lender = lender;
        }

        private final Amount<Currency> amount;

        private final Party borrower;

        private final String externalId;

        private final Party lender;

        private final Step INITIALISING = new Step("Performing Initial Steps.");

        private final Step BUILDING = new Step("Building Transaction.");

        private final Step SIGNING = new Step("Signing transaction.");

        private final Step COLLECTING = new Step("Collecting counterparty signature.") {

            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };

        private final Step FINALISING = new Step("Finalising transaction.") {

            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        private final ProgressTracker progressTracker = new ProgressTracker(INITIALISING, BUILDING, SIGNING, COLLECTING, FINALISING);

        @Override
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            progressTracker.setCurrentStep(INITIALISING);
            final IOUState newState = new IOUState(amount, borrower, lender, new UniqueIdentifier(externalId));
            final List<PublicKey> requiredSigners = newState.getParticipantKeys();
            final FlowSession otherFlow = initiateFlow(borrower);
            final PublicKey ourSigningKey = getOurIdentity().getOwningKey();
            progressTracker.setCurrentStep(BUILDING);
            final TransactionBuilder utx = new TransactionBuilder(getFirstNotary())
                    .addCommand(new IOUContract.Commands.Issue(), requiredSigners)
                    .setTimeWindow(getServiceHub().getClock().instant(), Duration.ofMinutes(5))
                    .addOutputState(newState, IOUContract.ID);
            progressTracker.setCurrentStep(SIGNING);
            utx.verify(getServiceHub());
            final List<PublicKey> signingKeys = new ImmutableList.Builder<PublicKey>().add(ourSigningKey).build();
            final SignedTransaction ptx = getServiceHub().signInitialTransaction(utx, signingKeys);
            progressTracker.setCurrentStep(COLLECTING);
            final ImmutableSet<FlowSession> sessions = ImmutableSet.of(otherFlow);
            final SignedTransaction stx = subFlow(new CollectSignaturesFlow(ptx, sessions, signingKeys, COLLECTING.childProgressTracker()));
            progressTracker.setCurrentStep(FINALISING);
            return subFlow(new FinalityFlow(stx, sessions, FINALISING.childProgressTracker()));
        }
    }

    @InitiatedBy(Initiator.class)
    public static class Responder extends FlowLogic<SignedTransaction> {

        private final FlowSession otherFlow;

        public Responder(FlowSession otherFlow) {
            this.otherFlow = otherFlow;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            final SignedTransaction stx = subFlow(new IOUBaseFlow.SignTxFlowNoChecking(otherFlow, SignTransactionFlow.Companion.tracker()));
            return subFlow(new ReceiveFinalityFlow(otherFlow, stx.getId()));
        }
    }
}
