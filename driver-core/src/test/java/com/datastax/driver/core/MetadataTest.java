/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.policies.WhiteListPolicy;
import com.datastax.driver.core.utils.CassandraVersion;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import static com.datastax.driver.core.Assertions.assertThat;
import static com.datastax.driver.core.CreateCCM.TestMode.PER_METHOD;
import static com.datastax.driver.core.TestUtils.nonDebouncingQueryOptions;
import static com.datastax.driver.core.TestUtils.waitForUp;
import static org.junit.Assert.assertEquals;

@CreateCCM(PER_METHOD)
public class MetadataTest extends CCMTestsSupport {

    /**
     * <p>
     * Validates that when the topology of the cluster changes that Cluster Metadata is properly updated.
     * </p>
     * <p/>
     * <p/>
     * This test does the following:
     * <p/>
     * <ol>
     * <li>Creates a 3 node cluster and capture token range data from the {@link Cluster}'s {@link Metadata}</li>
     * <li>Decommission node 3.</li>
     * <li>Validates that the token range data was updated to reflect node 3 leaving and the other nodes
     * taking on its token range.</li>
     * <li>Adds a new node, node 4.</li>
     * <li>Validates that the token range data was updated to reflect node 4 joining the {@link Cluster} and
     * the token ranges reflecting this.</li>
     * </ol>
     *
     * @test_category metadata:token
     * @expected_result cluster metadata is properly updated in response to node remove and add events.
     * @jira_ticket JAVA-312
     * @since 2.0.10, 2.1.5
     */
    @Test(groups = "long")
    @CCMConfig(numberOfNodes = 3, dirtiesContext = true, createCluster = false)
    public void should_update_metadata_on_topology_change() {
        Cluster cluster = register(Cluster.builder()
                .addContactPoints(getContactPoints().get(0))
                .withPort(ccm().getBinaryPort())
                .withQueryOptions(nonDebouncingQueryOptions())
                .build());
        Session session = cluster.connect();

        String keyspace = "test";
        session.execute("CREATE KEYSPACE " + keyspace + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        Metadata metadata = cluster.getMetadata();

        // Capture all Token data.
        assertThat(metadata.getTokenRanges()).hasSize(3);
        Map<Host, Token> tokensForHost = getTokenForHosts(metadata);

        // Capture host3s token and range before we take it down.
        Host host3 = TestUtils.findHost(cluster, 3);
        Token host3Token = tokensForHost.get(host3);

        ccm().decommission(3);
        ccm().remove(3);

        // Ensure that the token ranges were updated, there should only be 2 ranges now.
        assertThat(metadata.getTokenRanges()).hasSize(2);

        // The token should not be present for any Host.
        assertThat(getTokenForHosts(metadata)).doesNotContainValue(host3Token);

        // The ring should be fully accounted for.
        assertThat(cluster).hasValidTokenRanges("test");
        assertThat(cluster).hasValidTokenRanges();

        // Add an additional node.
        ccm().add(4);
        ccm().start(4);
        waitForUp(TestUtils.IP_PREFIX + '4', cluster);

        // Ensure that the token ranges were updated, there should only be 3 ranges now.
        assertThat(metadata.getTokenRanges()).hasSize(3);

        Host host4 = TestUtils.findHost(cluster, 4);
        TokenRange host4Range = metadata.getTokenRanges(keyspace, host4).iterator().next();

        // Ensure no host token range intersects with node 4.
        for (Host host : metadata.getAllHosts()) {
            if (!host.equals(host4)) {
                TokenRange hostRange = metadata.getTokenRanges(keyspace, host).iterator().next();
                assertThat(host4Range).doesNotIntersect(hostRange);
            }
        }

        // The ring should be fully accounted for.
        assertThat(cluster).hasValidTokenRanges("test");
        assertThat(cluster).hasValidTokenRanges();
    }

    /**
     * Test that the RPC port is not discovered, and that the configured one is used.
     * The broadcast port should still be discovered if it is available.
     */
    @Test(groups = "long")
    @CassandraVersion("4.0.0")
    @CCMConfig(numberOfNodes = 1, dirtiesContext = true, createCluster = false)
    public void should_not_discover_server_ports() throws Exception {
        Cluster cluster = register(Cluster.builder()
                .addContactPoints(getContactPoints().get(0))
                .withPort(ccm().getBinaryPort())
                .withQueryOptions(nonDebouncingQueryOptions())
                .withLoadBalancingPolicy(new WhiteListPolicy(new RoundRobinPolicy(), ImmutableList.of(new InetSocketAddress(getContactPoints().get(0), ccm().getBinaryPort()))))
                .build());
        Session session = cluster.connect();
        session.execute(new SimpleStatement("insert into system.peers_v2 (peer, peer_port, data_center, host_id, native_address, native_port, preferred_ip, preferred_port, rack, tokens) VALUES"
                + "('127.1.1.2', 666, 'datacenter1', 123e4567-e89b-12d3-a456-426655440000, '127.1.1.1', 667, '127.1.1.1', 666, 'rack1', {'3074457345618258601'})").setConsistencyLevel(ConsistencyLevel.ALL).enableTracing());
        session.close();
        cluster.close();

        cluster = register(Cluster.builder()
                .addContactPoints(getContactPoints().get(0))
                .withPort(ccm().getBinaryPort())
                .withQueryOptions(nonDebouncingQueryOptions())
                .withLoadBalancingPolicy(new WhiteListPolicy(new RoundRobinPolicy(), ImmutableList.of(new InetSocketAddress(getContactPoints().get(0), ccm().getBinaryPort()))))
                .build());
        session = cluster.connect();
        InetAddress expectedAddress = InetAddress.getByName("127.1.1.2");
        for (Host h : cluster.getMetadata().getAllHosts())
        {
            if (h.getSocketAddress().getAddress().equals(expectedAddress))
            {
                assertEquals(expectedAddress, h.getSocketAddress().getAddress());
                assertEquals(ccm().getBinaryPort(), h.getSocketAddress().getPort());
                assertEquals(InetAddress.getByName("127.1.1.2"), h.getBroadcastAddressOptPort().address);
                assertEquals(ccm().getStoragePort(), h.getBroadcastAddressOptPort().getPort());
            }
        }
    }

    /**
     * Test that the RPC port is discovered, and that the configured one is ignored.
     * The broadcast port should always be discovered.
     */
    @Test(groups = "long")
    @CassandraVersion("4.0.0")
    @CCMConfig(numberOfNodes = 1, dirtiesContext = true, createCluster = false)
    public void should_discover_server_ports() throws Exception {
        Cluster cluster = register(Cluster.builder()
                .addContactPoints(getContactPoints().get(0))
                .withPort(ccm().getBinaryPort())
                .withQueryOptions(nonDebouncingQueryOptions())
                .withLoadBalancingPolicy(new WhiteListPolicy(new RoundRobinPolicy(), ImmutableList.of(new InetSocketAddress(getContactPoints().get(0), ccm().getBinaryPort()))))
                .allowServerPortDiscovery()
                .build());
        Session session = cluster.connect();
        session.execute(new SimpleStatement("insert into system.peers_v2 (peer, peer_port, data_center, host_id, native_address, native_port, preferred_ip, preferred_port, rack, tokens) VALUES"
                                      + "('127.1.1.2', 666, 'datacenter1', 123e4567-e89b-12d3-a456-426655440000, '127.1.1.1', 667, '127.1.1.1', 666, 'rack1', {'3074457345618258601'})").setConsistencyLevel(ConsistencyLevel.ALL).enableTracing());
        session.close();
        cluster.close();

        cluster = register(Cluster.builder()
                .addContactPoints(getContactPoints().get(0))
                .withPort(ccm().getBinaryPort())
                .withQueryOptions(nonDebouncingQueryOptions())
                .withLoadBalancingPolicy(new WhiteListPolicy(new RoundRobinPolicy(), ImmutableList.of(new InetSocketAddress(getContactPoints().get(0), ccm().getBinaryPort()))))
                .allowServerPortDiscovery()
                .build());
        session = cluster.connect();
        InetAddress expectedAddress = InetAddress.getByName("127.1.1.2");
        for (Host h : cluster.getMetadata().getAllHosts())
        {
            System.out.println(h.getBroadcastAddressOptPort());
            System.out.println(h.getSocketAddress());
            if (h.getSocketAddress().getAddress().equals(expectedAddress))
            {
                assertEquals(expectedAddress, h.getSocketAddress().getAddress());
                assertEquals(666, h.getSocketAddress().getPort());
                assertEquals(InetAddress.getByName("127.1.1.2"), h.getBroadcastAddressOptPort().address);
                assertEquals(667, h.getBroadcastAddressOptPort().getPort());
            }
        }
    }

    /**
     * @return A mapping of Host -> Token for each Host in the given {@link Metadata}
     */
    private Map<Host, Token> getTokenForHosts(Metadata metadata) {
        Map<Host, Token> tokensByHost = Maps.newHashMap();
        for (Host host : metadata.getAllHosts()) {
            tokensByHost.put(host, host.getTokens().iterator().next());
        }
        return tokensByHost;
    }

    @Test(groups = "unit")
    public void handleId_should_lowercase_unquoted_alphanumeric_identifiers() {
        assertThat(Metadata.handleId("FooBar1")).isEqualTo("foobar1");
        assertThat(Metadata.handleId("Foo_Bar_1")).isEqualTo("foo_bar_1");
    }

    @Test(groups = "unit")
    public void handleId_should_unquote_and_preserve_case_of_quoted_identifiers() {
        assertThat(Metadata.handleId("\"FooBar1\"")).isEqualTo("FooBar1");
        assertThat(Metadata.handleId("\"Foo_Bar_1\"")).isEqualTo("Foo_Bar_1");
        assertThat(Metadata.handleId("\"Foo Bar 1\"")).isEqualTo("Foo Bar 1");
    }

    @Test(groups = "unit")
    public void handleId_should_unescape_duplicate_double_quotes_in_quoted_identifiers() {
        assertThat(Metadata.handleId("\"Foo\"\"Bar\"")).isEqualTo("Foo\"Bar");
    }

    @Test(groups = "unit")
    public void handleId_should_preserve_unquoted_non_alphanumeric_identifiers() {
        assertThat(Metadata.handleId("Foo Bar")).isEqualTo("Foo Bar");
    }

    @Test(groups = "unit")
    public void escapeId_should_not_quote_lowercase_identifiers() {
        String id = "this_does_not_need_quoting_0123456789abcdefghijklmnopqrstuvwxyz";
        assertThat(Metadata.quoteIfNecessary(id)).isEqualTo(id);
    }

    @Test(groups = "unit")
    public void escapeId_should_quote_non_lowercase_identifiers() {
        assertThat(Metadata.quoteIfNecessary("This_Needs_Quoting_1234")).isEqualTo("\"This_Needs_Quoting_1234\"");
        assertThat(Metadata.quoteIfNecessary("This Needs Quoting 1234!!")).isEqualTo("\"This Needs Quoting 1234!!\"");
    }

    @Test(groups = "unit")
    public void escapeId_should_quote_reserved_cql_keywords() {
        assertThat(Metadata.quoteIfNecessary("columnfamily")).isEqualTo("\"columnfamily\"");
    }

}
