package io.fabric8.commands;

import static io.fabric8.utils.FabricValidations.validateContainerName;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Command;

import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import io.fabric8.api.Container;
import io.fabric8.api.ContainerProvider;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.FabricAuthenticationException;
import io.fabric8.api.FabricService;
import io.fabric8.utils.shell.ShellUtils;

/**
 * Class for the container-restart action.
 * Created by avano on 24.3.16.
 */

@Command(name = ContainerRestart.FUNCTION_VALUE, scope = ContainerRestart.SCOPE_VALUE, description = ContainerRestart.DESCRIPTION,
		detailedDescription = "classpath:containerRestart.txt")
public class ContainerRestartAction extends AbstractContainerLifecycleAction {
	private static final Logger LOGGER = LoggerFactory.getLogger(ContainerRestartAction.class);
	private static final long TIMEOUT = 120000L;

	private CuratorFramework curator;
	private BundleContext bundleContext;

	private boolean restartCurrentContainer = false;
	private List<Container> stoppedFabricContainers = new ArrayList<>();
	private List<Container> restartedFabricContainers = new ArrayList<>();
	private List<Container> restartedRemoteContainers = new ArrayList<>();
	private List<Container> failedContainers = new ArrayList<>();

	/**
	 * Constructor.
	 *
	 * @param fabricService fabric service
	 * @param curator curator for accessing ZK
	 * @param bundleContext bundle context for restarting bundles
	 */
	public ContainerRestartAction(FabricService fabricService, CuratorFramework curator, BundleContext bundleContext) {
		super(fabricService);
		this.curator = curator;
		this.bundleContext = bundleContext;
	}

	@Override
	protected Object doExecute() throws Exception {
		final List<Container> containerList = getEligibleContainers(super.expandGlobNames(containers));

		// First execute stop on eligible containers
		for (Container c : containerList) {
			try {
				fabricService.stopContainer(c, force);
				stoppedFabricContainers.add(c);
			} catch (UnsupportedOperationException uoe) {
				// Use case for managed containers that are not created using Fabric - joined containers for example
				if (uoe.getMessage().contains("has not been created using Fabric")) {
					restartRemoteContainer(c);
				} else {
					LOGGER.error(c.getId() + ": " + uoe.getMessage());
					failedContainers.add(c);
				}
			} catch (Exception ex) {
				LOGGER.error(c.getId() + ": " + ex.getMessage());
				failedContainers.add(c);
			}
		}

		// Wait for the containers to stop
		for (Container c : stoppedFabricContainers) {
			final long startedAt = System.currentTimeMillis();
			// Wait until the zk node /fabric/registry/containers/status/<container name>/pid disappears
			// Or until the time is up
			// Let this cycle pass atleast 1 time to make it more reliable
			do {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			} while (!Thread.interrupted() && startedAt + TIMEOUT > System.currentTimeMillis()
					&& exists(curator, "/fabric/registry/containers/status/" + c.getId() + "/pid") != null);
		}

		// Start the restarted fabric containers back
		for (Container c : stoppedFabricContainers) {
			try {
				fabricService.startContainer(c, force);
				restartedFabricContainers.add(c);
			} catch (Exception ex) {
				LOGGER.error(c.getId() + ": " + ex.getMessage());
				failedContainers.add(c);
			}
		}

		printRestartedContainers();
		printFailedContainers();

		if (restartCurrentContainer) {
			System.setProperty("karaf.restart.jvm", "true");
			LOGGER.warn("Automated JVM restart support is not universally available. If your jvm doesn't support it you are required to manually "
					+ "restart the container");
			try {
				bundleContext.getBundle(0).stop();
			} catch (BundleException e) {
				LOGGER.error(fabricService.getCurrentContainerName() + ": Error when forcing a JVM restart", e);
			}
		}

		return null;
	}

	/**
	 * Tries to restart the remote container using JMX.
	 *
	 * @param c container to restart
	 */
	private void restartRemoteContainer(Container c) {
		final MBeanServerConnection connection;
		try {
			final JMXConnector connector = createConnector(c);
			connection = connector.getMBeanServerConnection();

			// Because this has the uuid in the name, we need to find it first
			ObjectName frameworkObjectMode = null;
			final ObjectName fabricObjectName = new ObjectName("io.fabric8:type=Fabric");
			final Set<ObjectName> mbeans = connection.queryNames(null, null);

			for (ObjectName mbeanName : mbeans) {
				if (mbeanName.toString().contains("osgi.core:type=framework")) {
					frameworkObjectMode = mbeanName;
					break;
				}
			}

			// Set the system property via JMX
			connection.invoke(fabricObjectName, "setSystemProperty", new Object[] {"karaf.restart.jvm", "true"},
					new String[] {String.class.getName(), String.class.getName()});

			LOGGER.warn(c.getId() + ": Automated JVM restart support is not universally available. If your jvm doesn't support it you are required "
					+ "to manually restart the container");
			// Invoke bundle(0) stop via JMX, we can't invoke stopBundle(0) directly because of ACL
			connection.invoke(frameworkObjectMode, "stopBundles", new Object[] {new long[] {0}}, new String[] {long[].class.getName()});
			restartedRemoteContainers.add(c);
		} catch (Exception ex) {
			LOGGER.error(ex.toString());
		}
	}

	/**
	 * Prints the restarted containers list.
	 */
	private void printRestartedContainers() {
		final ArrayList<Container> restartedContainers = new ArrayList<>();
		restartedContainers.addAll(restartedFabricContainers);
		restartedContainers.addAll(restartedRemoteContainers);
		if (restartedContainers.size() == 0) {
			return;
		}

		final StringBuilder containers = new StringBuilder("The list of restarted containers: [");
		for (Container c : restartedContainers) {
			containers.append(c.getId());
			containers.append(", ");
		}
		containers.setLength(containers.length() - 2);
		containers.append("]");
		System.out.println(containers.toString());
	}

	/**
	 * Prints the failed containers list.
	 */
	private void printFailedContainers() {
		if (failedContainers.size() == 0) {
			return;
		}

		final StringBuilder containers = new StringBuilder("The list of failed containers: [");
		for (Container c : failedContainers) {
			containers.append(c.getId());
			containers.append(", ");
		}
		containers.setLength(containers.length() - 2);
		containers.append("]");
		System.out.println(containers.toString());
	}

	/**
	 * Pick the containers eligible for restart from the collection of container names.
	 *
	 * @param names container names
	 * @return list of containers eligible for restart
	 */
	private List<Container> getEligibleContainers(Collection<String> names) {
		final List<Container> containerList = new ArrayList<>();
		for (String containerName : names) {
			validateContainerName(containerName);
			if (fabricService.getContainer(containerName).isEnsembleServer() && !force) {
				System.out.println("Container " + containerName + " is an ensemble member. If you want to restart it, use the --force flag");
				continue;
			}
			if (containerName.equals(fabricService.getCurrentContainerName())) {
				if (fabricService.getCurrentContainer().isRoot() && !isSshContainer(fabricService.getCurrentContainer())) {
					restartCurrentContainer = true;
				} else {
					LOGGER.warn("Container " + containerName + " can't be restarted from itself");
				}
				continue;
			}
			if (!fabricService.getContainer(containerName).isManaged()) {
				restartRemoteContainer(fabricService.getContainer(containerName));
				continue;
			}
			containerList.add(fabricService.getContainer(containerName));
		}
		return containerList;
	}

	/**
	 * Checks if the container is an SSH container.
	 *
	 * @param container container
	 * @return true/false
	 */
	private boolean isSshContainer(Container container) {
		final CreateContainerMetadata metadata = container.getMetadata();
		final String type = metadata != null ? metadata.getCreateOptions().getProviderType() : null;

		if (type == null) {
			return false;
		}

		final ContainerProvider provider = fabricService.getProvider(type);

		return provider != null && "ssh".equals(provider.getScheme());
	}

	/**
	 * Gets the auth credentials.
	 *
	 * @return hashmap with credentials
	 */
	private HashMap<String, String[]> getAuth() {
		user = user != null && !user.isEmpty() ? user : ShellUtils.retrieveFabricUser(session);
		password = password != null ? password : ShellUtils.retrieveFabricUserPassword(session);
		final String[] credentials = new String[] {user, password};
		final HashMap<String, String[]> env = new HashMap<>();
		env.put(JMXConnector.CREDENTIALS, credentials);
		return env;
	}

	/**
	 * Creates the JMX connector to the specified container.
	 *
	 * @param container container to connect to
	 * @return jmx connector instance
	 * @throws IOException if unable to connect
	 */
	private JMXConnector createConnector(Container container) throws IOException {
		final JMXServiceURL target = new JMXServiceURL(container.getJmxUrl());
		JMXConnector connector;
		try {
			connector = JMXConnectorFactory.connect(target, getAuth());
		} catch (FabricAuthenticationException | SecurityException ex) {
			user = null;
			password = null;
			promptForJmxCredentialsIfNeeded(container);
			connector = JMXConnectorFactory.connect(target, getAuth());
		}

		return connector;
	}

	/**
	 * Prompts the user for username & password.
	 */
	private void promptForJmxCredentialsIfNeeded(Container container) throws IOException {
		// If the username was not configured via cli, then prompt the user for the values
		if (user == null || user.isEmpty()) {
			user = ShellUtils.readLine(session, "JMX Login for container " + container.getId() + ": ", false);
		}

		if (password == null) {
			password = ShellUtils.readLine(session, "JMX Password for " + user + "@" + container.getId() + ": ", true);
		}
	}
}
