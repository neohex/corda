package net.corda.client.rpc;

import net.corda.core.contracts.Amount;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowHandle;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.finance.flows.AbstractCashFlow;
import net.corda.finance.flows.CashIssueFlow;
import net.corda.finance.flows.CashPaymentFlow;
import net.corda.finance.schemas.CashSchemaV1;
import net.corda.node.internal.Node;
import net.corda.node.internal.StartedNode;
import net.corda.nodeapi.User;
import net.corda.testing.CoreTestUtils;
import net.corda.testing.internal.NodeBasedTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static kotlin.test.AssertionsKt.assertEquals;
import static net.corda.finance.Currencies.DOLLARS;
import static net.corda.finance.contracts.GetBalances.getCashBalance;
import static net.corda.node.services.FlowPermissions.startFlowPermission;
import static net.corda.testing.TestConstants.getALICE;

public class CordaRPCJavaClientTest extends NodeBasedTest {
    public CordaRPCJavaClientTest() {
        super(Arrays.asList("net.corda.finance.contracts", CashSchemaV1.class.getPackage().getName()));
    }

    private List<String> perms = Arrays.asList(startFlowPermission(CashPaymentFlow.class), startFlowPermission(CashIssueFlow.class));
    private Set<String> permSet = new HashSet<>(perms);
    private User rpcUser = new User("user1", "test", permSet);

    private StartedNode<Node> node;
    private CordaRPCClient client;
    private RPCConnection<CordaRPCOps> connection = null;
    private CordaRPCOps rpcProxy;

    private void login(String username, String password) {
        connection = client.start(username, password);
        rpcProxy = connection.getProxy();
    }

    @Before
    public void setUp() throws ExecutionException, InterruptedException {
        node = startNode(getALICE().getName(), 1, singletonList(rpcUser));
        client = new CordaRPCClient(requireNonNull(node.getInternals().getConfiguration().getRpcAddress()));
    }

    @After
    public void done() throws IOException {
        connection.close();
    }

    @Test
    public void testLogin() {
        login(rpcUser.getUsername(), rpcUser.getPassword());
    }

    @Test
    public void testCashBalances() throws NoSuchFieldException, ExecutionException, InterruptedException {
        login(rpcUser.getUsername(), rpcUser.getPassword());

        FlowHandle<AbstractCashFlow.Result> flowHandle = rpcProxy.startFlowDynamic(CashIssueFlow.class,
                DOLLARS(123), OpaqueBytes.of("1".getBytes()),
                CoreTestUtils.chooseIdentity(node.getInfo()));
        System.out.println("Started issuing cash, waiting on result");
        flowHandle.getReturnValue().get();

        Amount<Currency> balance = getCashBalance(rpcProxy, Currency.getInstance("USD"));
        System.out.print("Balance: " + balance + "\n");

        assertEquals(DOLLARS(123), balance, "matching");
    }
}
