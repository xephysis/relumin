package com.behase.relumin.support;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import redis.clients.jedis.Jedis;

import com.behase.relumin.model.ClusterNode;
import com.behase.relumin.util.JedisUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import lombok.extern.slf4j.Slf4j;

/**
 * Clone of redis-trib.rb
 * @author Ryosuke Hasebe
 *
 */
@Slf4j
public class TribClusterNode implements Closeable {
	private Jedis jedis;
	private ClusterNode info;
	private boolean dirty = false;
	private List<ClusterNode> friends = Lists.newArrayList();
	private Set<Integer> tmpSlots = Sets.newTreeSet();

	public TribClusterNode(String hostAndPort) {
		String[] hostAndPortArray = StringUtils.split(hostAndPort, ":");
		checkArgument(hostAndPortArray.length >= 2, "Invalid IP or Port. Use IP:Port format");

		info = new ClusterNode();
		info.setHostAndPort(hostAndPort);
	}

	public List<ClusterNode> getFriends() {
		return friends;
	}

	public String getServedSlots() {
		return info.getServedSlots();
	}

	public boolean hasFlag(String flag) {
		return info.getFlags().contains(flag);
	}

	public void connect() throws RedisTribException {
		connect(false);
	}

	public void connect(boolean abort) throws RedisTribException {
		if (jedis != null) {
			return;
		}

		try {
			jedis = JedisUtils.getJedisByHostAndPort(info.getHostAndPort());
			if (!"PONG".equalsIgnoreCase(jedis.ping())) {
				log.error("Invalid PONG-message.");
				throw new RedisTribException("Invalid PONG-message.", false);
			}
		} catch (Exception e) {
			log.error("Failed to connect to node.");
			if (abort) {
				throw new RedisTribException("Failed to connect to node.", false);
			}
			jedis = null;
		}
	}

	public void assertCluster() throws RedisTribException {
		Map<String, String> infoResult = JedisUtils.parseInfoResult(jedis.info());
		log.debug("cluster info={}", infoResult);
		String clusterEnabled = infoResult.get("cluster_enabled");
		if (StringUtils.isBlank(clusterEnabled) || StringUtils.equals(clusterEnabled, "0")) {
			throw new RedisTribException(String.format("%s is not configured as a cluster node.", info.getHostAndPort()), false);
		}
	}

	public void assertEmpty() throws RedisTribException {
		Map<String, String> infoResult = JedisUtils.parseInfoResult(jedis.info());
		Map<String, String> clusterInfoResult = JedisUtils.parseInfoResult(jedis.clusterInfo());
		log.debug("cluster info={}", infoResult);
		if (infoResult.get("db0") != null || !StringUtils.equals(clusterInfoResult.get("cluster_known_nodes"), "1")) {
			throw new RedisTribException(String.format("%s is not empty. Either the node already knows other nodes (check with CLUSTER NODES) or contains some key in database 0.", info.getHostAndPort()), false);
		}
	}

	public void loadInfo() throws RedisTribException {
		loadInfo(false);
	}

	public void loadInfo(boolean getFriend) throws RedisTribException {
		connect();

		String hostAndPort = info.getHostAndPort();
		List<ClusterNode> nodes = JedisUtils.parseClusterNodesResult(jedis.clusterNodes(), hostAndPort);
		nodes.forEach(v -> {
			if (v.hasFlag("myself")) {
				info = v;
				info.setHostAndPort(hostAndPort);
			} else if (getFriend) {
				friends.add(v);
			}
		});
	}

	public void addTmpSlots(Collection<Integer> slots) {
		if (slots.isEmpty()) {
			return;
		}
		tmpSlots.addAll(tmpSlots);
		dirty = true;
	}

	public void setAsReplica(String masterNodeId) {
		info.setMasterNodeId(masterNodeId);
		dirty = true;
	}

	public void flushNodeConfig() {
		if (!dirty) {
			return;
		}
		if (StringUtils.isBlank(info.getMasterNodeId())) {
			try {
				jedis.clusterReplicate(info.getMasterNodeId());
			} catch (Exception e) {
				log.error("Replicate error.", e);
				// If the cluster did not already joined it is possible that
				// the slave does not know the master node yet. So on errors
				// we return ASAP leaving the dirty flag set, to flush the
				// config later.
				return;
			}
		} else {
			int[] intArray = new int[tmpSlots.size()];
			int i = 0;
			for (Integer item : tmpSlots) {
				intArray[i] = item;
				i++;
			}
			jedis.clusterAddSlots(intArray);
			tmpSlots.clear();
		}
		dirty = false;
	}

	public String getConfigSignature() {
		List<String> config = Lists.newArrayList();

		String result = jedis.clusterNodes();
		for (String line : StringUtils.split(result, "\n")) {
			String[] lineArray = StringUtils.split(line);

			List<String> slots = Lists.newArrayList();
			for (int i = 8; i < lineArray.length; i++) {
				slots.add(lineArray[i]);
			}
			slots.stream().filter(v -> {
				return !StringUtils.startsWith(v, "[");
			}).collect(Collectors.toList());

			if (slots.size() > 0) {
				Collections.sort(slots);
				config.add(Joiner.on(":").join(lineArray[0], Joiner.on(",").join(slots)));
			}
		}

		Collections.sort(config);
		return Joiner.on("|").join(config);
	}

	public ClusterNode getInfo() {
		return info;
	}

	public boolean isDirty() {
		return dirty;
	}

	public Jedis getJedis() {
		return jedis;
	}

	@Override
	public void close() throws IOException {
		if (jedis != null) {
			jedis.close();
		}
	}

	@Override
	public String toString() {
		return info.getHostAndPort();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}

		TribClusterNode other = (TribClusterNode)obj;
		return StringUtils.equals(getInfo().getHostAndPort(), other.getInfo().getHostAndPort());
	}
}
