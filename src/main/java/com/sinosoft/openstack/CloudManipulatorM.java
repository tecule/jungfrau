package com.sinosoft.openstack;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.OSClient.OSClientV2;
import org.openstack4j.api.OSClient.OSClientV3;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.QuotaSet;
import org.openstack4j.model.compute.RebootType;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerUpdateOptions;
import org.openstack4j.model.compute.VNCConsole;
import org.openstack4j.model.compute.VNCConsole.Type;
import org.openstack4j.model.compute.VolumeAttachment;
import org.openstack4j.model.compute.actions.LiveMigrateOptions;
import org.openstack4j.model.compute.ext.Hypervisor;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Pool;
import org.openstack4j.model.network.Port;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.Subnet;
import org.openstack4j.model.network.options.PortListOptions;
import org.openstack4j.model.storage.block.BlockLimits;
import org.openstack4j.model.storage.block.BlockLimits.Absolute;
import org.openstack4j.model.storage.block.BlockQuotaSet;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.telemetry.MeterSample;
import org.openstack4j.model.telemetry.SampleCriteria;
import org.openstack4j.openstack.networking.domain.NeutronFloatingIP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sinosoft.openstack.exception.CloudException;
import com.sinosoft.openstack.type.ActionResult;
import com.sinosoft.openstack.type.ServerInfo;
import com.sinosoft.openstack.type.telemetry.ServerSamples;

public abstract class CloudManipulatorM implements CloudManipulator {
	private static Logger logger = LoggerFactory.getLogger(CloudManipulatorM.class);
	
	/*
	 * projectId is initialized in the subclass.
	 */
	protected String projectId;
	/*
	 * PUBLIC_NETWORK_ID is initialized in the subclass.
	 */
	protected String PUBLIC_NETWORK_ID;
	/*
	 * projectClient is used in this abstract class only, and is initialized in the subclass.
	 */
	@SuppressWarnings("rawtypes")
	protected OSClient projectClient;
	/*
	 * projectClientM2 is the client used for identity V2 API, and is initialized in the subclass.
	 */
	protected OSClientV2 projectClientM2;
	/*
	 * projectClientM3 is the client used for identity V3 API, and is initialized in the subclass.
	 */
	protected OSClientV3 projectClientM3;

	@Override
	public abstract String createProject(String projectName, String projectDescription, int instanceQuota, int cpuQuota,
			int memoryQuota);

	@Override
	public abstract QuotaSet updateComputeServiceQuota(int instanceQuota, int cpuQuota, int memoryQuota);

	@Override
	public Absolute getBlockStorageQuotaUsage() {
		try {
			BlockLimits limits = projectClient.blockStorage().getLimits();
			Absolute absolute = limits.getAbsolute();
			return absolute;
		} catch (Exception e) {
			throw new CloudException("获取块存储配额使用量发生错误。", e);
		}
	}

	@Override
	public BlockQuotaSet updateBlockStorageQuota(int volumes, int gigabytes) {
		try {
			BlockQuotaSet quota = projectClient.blockStorage().quotaSets()
					.updateForTenant(projectId, Builders.blockQuotaSet().volumes(volumes).gigabytes(gigabytes).build());
			return quota;
		} catch (Exception e) {
			throw new CloudException("更新块存储配额发生错误。", e);
		}
	}

	@Override
	public abstract ActionResult deleteProject();

	@Override
	public List<? extends Volume> getVolumes() {
		try {
			List<? extends Volume> volumes = projectClient.blockStorage().volumes().list();
			return volumes;
		} catch (Exception e) {
			throw new CloudException("获取卷列表发生错误。", e);
		}
	}

	@Override
	public Volume createVolume(String volumeName, String volumeDescription, int volumeSize) {
		try {
			/*
			 * TODO volume name & description with Chinese characters not supported in openstack4j 3.0.2.
			 */
			Volume volume = projectClient.blockStorage().volumes()
					.create(Builders.volume().name(volumeName).description(volumeDescription).size(volumeSize).build());
			return volume;
		} catch (Exception e) {
			String message = "";
			if (e.getMessage().indexOf("VolumeLimitExceeded") >= 0) {
				message = "已经创建的卷达到了系统允许的最大配额，请增加块存储配额，或删除不需要的卷。";
			} else {
				message = "创建卷发生错误。";
			}

			throw new CloudException(message, e);
		}
	}

	@Override
	public boolean modifyVolume(String volumeId, String volumeName, String volumeDescription) {
		try {
			// modification will return true as long as cinder-api is working
			ActionResponse response = projectClient.blockStorage().volumes()
					.update(volumeId, volumeName, volumeDescription);
			if (false == response.isSuccess()) {
				logger.error("修改卷信息失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("修改卷信息发生错误。", e);
		}
	}

	@Override
	public Volume getVolume(String volumeId) {
		try {
			Volume volume = projectClient.blockStorage().volumes().get(volumeId);
			return volume;
		} catch (Exception e) {
			throw new CloudException("获取卷发生错误。", e);
		}
	}

	@Override
	public boolean deleteVolume(String volumeId) {
		try {
			ActionResponse response = projectClient.blockStorage().volumes().delete(volumeId);
			if (false == response.isSuccess()) {
				logger.error("删除卷失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("删除卷发生错误。", e);
		}
	}

	@Override
	public boolean waitVolumeStatus(String volumeId, List<Volume.Status> statusList, int minute)
			throws InterruptedException {
		int sleepInterval = 6000;
		int sleepCount = minute * 60 * 1000 / sleepInterval;

		int loop = 0;
		while (loop < sleepCount) {
			Volume volume = getVolume(volumeId);
			if (null == volume) {
				return false;
			}

			if (volume.getStatus() != null) {
				for (Volume.Status status : statusList) {
					if (volume.getStatus() == status) {
						return true;
					}
				}
			}

			Thread.sleep(sleepInterval);
			loop++;
		}

		return false;
	}

	@Override
	public boolean waitVolumeDeleted(String volumeId, int minute) throws InterruptedException {
		int sleepInterval = 6000;
		int sleepCount = minute * 60 * 1000 / sleepInterval;

		int loop = 0;
		while (loop < sleepCount) {
			Volume volume = getVolume(volumeId);
			if (null == volume) {
				return true;
			}

			Thread.sleep(sleepInterval);
			loop++;
		}

		return false;
	}

	@Override
	public List<? extends Image> getImages() {
		try {
			List<? extends Image> images = projectClient.images().list();
			return images;
		} catch (Exception e) {
			throw new CloudException("获取镜像列表发生错误。", e);
		}
	}

	@Override
	public Image getImage(String imageId) {
		try {
			Image image = projectClient.images().get(imageId);
			return image;
		} catch (Exception e) {
			throw new CloudException("获取镜像发生错误。", e);
		}
	}

	@Override
	public boolean waitImageStatus(String imageId, org.openstack4j.model.image.Image.Status status, int minute)
			throws InterruptedException {
		int sleepInterval = 6000;
		int sleepCount = minute * 60 * 1000 / sleepInterval;

		int loop = 0;
		while (loop < sleepCount) {
			Image image = getImage(imageId);
			if (null == image) {
				return false;
			}

			if (image.getStatus() != null) {
				if (image.getStatus() == status) {
					return true;
				}
			}

			Thread.sleep(sleepInterval);
			loop++;
		}

		return false;
	}
	
	// @Override
	// public boolean waitImageDeleted(String imageId, int minute) throws InterruptedException {
	// int sleepInterval = 6000;
	// int sleepCount = minute * 60 * 1000 / sleepInterval;
	//
	// int loop = 0;
	// while (loop < sleepCount) {
	// Image image = getImage(imageId);
	// if (null == image) {
	// return true;
	// }
	//
	// Thread.sleep(sleepInterval);
	// loop++;
	// }
	//
	// return false;
	// }
	
	@Override
	public abstract Image updateImage(String imageId, String imageName, boolean publicity);

	@Override
	public boolean deleteImage(String imageId) {
		try {
			ActionResponse response = projectClient.images().delete(imageId);
			if (false == response.isSuccess()) {
				logger.error("删除镜像失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("删除镜像发生错误。", e);
		}
	}

	@Override
	public List<? extends Hypervisor> getHypervisors() {
		try {
			List<? extends Hypervisor> hypervisors = projectClient.compute().hypervisors().list();
			return hypervisors;
		} catch (Exception e) {
			throw new CloudException("获取计算节点列表发生错误。", e);
		}
	}

	@Override
	public List<? extends Server> getServers() {
		try {
			List<? extends Server> servers = projectClient.compute().servers().list();
			return servers;
		} catch (Exception e) {
			throw new CloudException("获取虚拟机实例列表发生错误。", e);
		}
	}
	
	@Override
	public Flavor getFlavor(int cpu, int memory, int disk) {
		try {
			Flavor flavor = null;
			List<? extends Flavor> flavors = projectClient.compute().flavors().list();
			Iterator<? extends Flavor> it = flavors.iterator();
			while (it.hasNext()) {
				Flavor f = (Flavor) it.next();
				if (f.getRam() == memory * 1024 && f.getDisk() == disk && f.getVcpus() == cpu) {
					flavor = f;
					break;
				}
			}

			return flavor;
		} catch (Exception e) {
			throw new CloudException("获取虚拟机配置发生错误。", e);
		}
	}

	@Override
	public Flavor createFlavor(int cpu, int memory, int disk) {
		try {
			Flavor flavor = null;
			String flavorName = "cpu" + cpu + "_mem" + memory + "_disk" + disk;
			// TODO why rxtxFactor != 1.0
			flavor = Builders.flavor().name(flavorName).ram(memory * 1024).vcpus(cpu).disk(disk).rxtxFactor(1.2f)
					.build();
			flavor = projectClient.compute().flavors().create(flavor);

			return flavor;
		} catch (Exception e) {
			throw new CloudException("创建虚拟机配置发生错误。", e);
		}
	}

	@Override
	public Server bootServer(String serverName, String flavorId, String imageId) {
		try {
			Server server = projectClient.compute().servers()
					.boot(Builders.server().name(serverName).flavor(flavorId).image(imageId).build());
			return server;
		} catch (Exception e) {
			throw new CloudException("创建虚拟机实例发生错误。", e);
		}
	}

	@Override
	public boolean waitServerStatus(String serverId, List<Server.Status> statusList, int minute) throws InterruptedException {
		int sleepInterval = 6000;
		int sleepCount = minute * 60 * 1000 / sleepInterval;

		int loop = 0;
		while (loop < sleepCount) {
			Server server = getServer(serverId);
			if (null == server) {
				return false;
			}

			if (server.getStatus() != null) {
				for (Server.Status status : statusList) {
					if ((server.getStatus() == status) && (null == server.getTaskState())) {
						return true;
					}
				}
			}

			Thread.sleep(sleepInterval);
			loop++;
		}

		return false;
	}

	@Override
	public boolean waitServerDeleted(String serverId, int minute) throws InterruptedException {
		int sleepInterval = 6000;
		int sleepCount = minute * 60 * 1000 / sleepInterval;

		int loop = 0;
		while (loop < sleepCount) {
			Server server = getServer(serverId);
			if (null == server) {
				return true;
			}

			Thread.sleep(sleepInterval);
			loop++;
		}

		return false;
	}

	@Override
	public Server getServer(String serverId) {
		try {
			Server server = projectClient.compute().servers().get(serverId);
			return server;
		} catch (Exception e) {
			throw new CloudException("获取虚拟机实例发生错误。", e);
		}
	}

	@Override
	public boolean startServer(String serverId) {
		try {
			ActionResponse response = projectClient.compute().servers().action(serverId, Action.START);
			if (false == response.isSuccess()) {
				logger.error("启动虚拟机失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("启动虚拟机发生错误。", e);
		}
	}

	@Override
	public boolean rebootServer(String serverId) {
		try {
			ActionResponse response = projectClient.compute().servers().reboot(serverId, RebootType.SOFT);
			if (false == response.isSuccess()) {
				logger.error("重启虚拟机失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("重启虚拟机发生错误。", e);
		}
	}

	@Override
	public boolean stopServer(String serverId) {
		try {
			ActionResponse response = projectClient.compute().servers().action(serverId, Action.STOP);
			if (false == response.isSuccess()) {
				logger.error("关闭虚拟机失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("关闭虚拟机发生错误。", e);
		}
	}

	@Override
	public boolean deleteServer(String serverId) {
		try {
			ActionResponse response = projectClient.compute().servers().delete(serverId);
			if (false == response.isSuccess()) {
				logger.error(response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("删除虚拟机发生错误。", e);
		}
	}

	@Override
	public VNCConsole getServerVNCConsole(String serverId) {
		try {
			VNCConsole console = projectClient.compute().servers().getVNCConsole(serverId, Type.NOVNC);
			return console;
		} catch (Exception e) {
			throw new CloudException("获取虚拟机控制台发生错误。", e);
		}
	}

	@Override
	public Server renameServer(String serverId, String newName) {
		try {
			Server server = projectClient.compute().servers()
					.update(serverId, ServerUpdateOptions.create().name(newName));
			return server;
		} catch (Exception e) {
			throw new CloudException("重命名虚拟机发生错误。", e);
		}
	}

	@Override
	public String createSnapshot(String serverId, String snapshotName) {
		try {
			String snapshotId = projectClient.compute().servers().createSnapshot(serverId, snapshotName);
			return snapshotId;
		} catch (Exception e) {
			throw new CloudException("创建快照发生错误。", e);
		}
	}

	@Override
	public boolean attachVolume(String serverId, String volumeId) {
		try {
			VolumeAttachment attachment = projectClient.compute().servers().attachVolume(serverId, volumeId, null);
			if (null == attachment) {
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("挂载卷发生错误。", e);
		}
	}

	@Override
	public boolean detachVolume(String serverId, String volumeId) {
		try {
			ActionResponse response = projectClient.compute().servers().detachVolume(serverId, volumeId);
			if (false == response.isSuccess()) {
				logger.error("卸载卷失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("卸载卷发生错误。", e);
		}
	}

	@Override
	public boolean liveMigrate(String serverId, String hypervisorName) {
		try {
//			// kilo use host name in live migration
//			String host = hypervisorName;
//			int fqdnDotPosition = hypervisorName.indexOf('.');
//			if (fqdnDotPosition >= 0) {
//				host = hypervisorName.substring(0, fqdnDotPosition);
//			}
//			ActionResponse response = projectClientM2.compute().servers().liveMigrate(serverId,
//					LiveMigrateOptions.create().host(host));
			
			// mitaka use FQDN in live migration
			ActionResponse response = projectClient.compute().servers()
					.liveMigrate(serverId, LiveMigrateOptions.create().host(hypervisorName));
			if (false == response.isSuccess()) {
				logger.error("迁移虚拟机实例失败：" + response.getFault());
				return false;
			}

			return true;
		} catch (Exception e) {
			throw new CloudException("迁移虚拟机实例发生错误。", e);
		}
	}


	@Override
	public ServerInfo getServerInfo(String serverId) {
		Server server = getServer(serverId);
		if (null == server) {
			return null;
		}

		String privateIp = "", floatingIp = "";
		Iterator<List<? extends Address>> it = server.getAddresses().getAddresses().values().iterator();
		while (it.hasNext()) {
			List<? extends Address> addresses = it.next();
			for (int i = 0; i < addresses.size(); i++) {
				/*
				 * use addresses.get(i).getType().equalsIgnoreCase("fixed") instead of addresses.get(i).getType() ==
				 * "fixed"
				 */
				if (addresses.get(i).getType().equalsIgnoreCase("fixed")) {
					// privateIp += addresses.get(i).getAddr() + ", ";
					privateIp = addresses.get(i).getAddr();
				} else if (addresses.get(i).getType().equalsIgnoreCase("floating")) {
					// floatingIp += addresses.get(i).getAddr() + ", ";
					floatingIp = addresses.get(i).getAddr();
				}
			}
		}
		// if (privateIp.length() > 2) {
		// privateIp = privateIp.substring(0, privateIp.length() - 2);
		// }
		// if (floatingIp.length() > 2) {
		// floatingIp = floatingIp.substring(0, floatingIp.length() - 2);
		// }

		String physicalMachine = server.getHypervisorHostname();

		ServerInfo info = new ServerInfo();
		info.setPrivateIp(privateIp);
		info.setFloatingIp(floatingIp);
		info.setPhysicalMachine(physicalMachine);
		return info;
	}
	
	/**
	 * get resource id by server and meter.
	 * 
	 * @param serverId
	 *            - server id
	 * @param meterName
	 *            - meter name
	 * @return resource id, return null if resource not found
	 * @author xiangqian
	 */
	protected String getResourceId(String serverId, String meterName) {
		List<String> serverResourceMeters = new ArrayList<String>(Arrays.asList("cpu_util", "memory.usage",
				"disk.read.bytes.rate", "disk.write.bytes.rate"));
		List<String> networkResourceMeters = new ArrayList<String>(Arrays.asList("network.outgoing.bytes.rate",
				"network.incoming.bytes.rate"));

		// get resource id
		String resourceId;
		if (serverResourceMeters.contains(meterName)) {
			resourceId = serverId;
		} else if (networkResourceMeters.contains(meterName)) {
			Server server = getServer(serverId);
			if (null == server) {
				logger.error("虚拟机实例不存在");
				return null;
			}

			// get network port resource id
			List<? extends Port> ports = projectClient.networking().port()
					.list(PortListOptions.create().deviceId(serverId));

			// TODO: assume ports length > 0
			if (ports.size() <= 0) {
				logger.error("虚拟机实例缺少网络端口");
				return null;
			}

			String networkResourceId = server.getInstanceName() + "-" + serverId + "-tap" + ports.get(0).getId();
			resourceId = networkResourceId.substring(0, 69);
		} else {
			logger.error("无效的监控项：" + meterName);
			return null;
		}

		return resourceId;
	}
	
	@Override
	public abstract String createAlarm(String serverId, String alarmName, String meterName, float threshold);

	@Override
	public abstract boolean updateAlarm(String alarmId, boolean enabled, float threshold);

	@Override
	public abstract boolean deleteAlarm(String alarmId);

	@Override
	public abstract String getAlarmState(String alarmId);
	
	private String convertUtcToLocal(String sampleTimestamp) throws ParseException {
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		Date localDate = formatter.parse(sampleTimestamp);
		formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String localFormat = formatter.format(localDate);

		return localFormat;
	}
	
	@Override
	public ServerSamples getSamples(String serverId, String meterName, long timestamp) {
		ServerSamples serverSamples = new ServerSamples();
		
		/*
		 * initialize result
		 */
		List<String> timeSeries = new ArrayList<String>();
		List<Float> samples = new ArrayList<Float>();
		serverSamples.setMeterName(meterName);
		serverSamples.setTimeSeries(timeSeries);
		serverSamples.setSamples(samples);

		String resourceId = getResourceId(serverId, meterName);
		if (null == resourceId) {
			return serverSamples;
		}

		try {
			SampleCriteria criteria = new SampleCriteria().resource(resourceId).timestamp(SampleCriteria.Oper.GT,
					timestamp);
			List<? extends MeterSample> meterSamples = projectClientM3.telemetry().meters().samples(meterName, criteria);
			/*
			 * invert result order
			 */
			for (int index = meterSamples.size() - 1; index >= 0; index--) {
				MeterSample sample = meterSamples.get(index);
				timeSeries.add(convertUtcToLocal(sample.getTimestamp()));
				samples.add(sample.getCounterVolume());
			}	

			serverSamples.setTimeSeries(timeSeries);
			serverSamples.setSamples(samples);
			return serverSamples;
		} catch (Exception e) {
			throw new CloudException("获取虚拟机负载发生错误。", e);
		}
	}

	@Override
	public List<String> getExternalIps() {
		try {
			List<String> floatingIpRange = new ArrayList<String>();

			Network publicNetwork = projectClientM3.networking().network().get(PUBLIC_NETWORK_ID);
			if (null == publicNetwork) {
				logger.error("获取浮动IP地址范围出错，外部网络不存在。");
				return floatingIpRange;
			}

			List<String> subnetIds = publicNetwork.getSubnets();
			if (1 != subnetIds.size()) {
				logger.error("获取浮动IP地址范围出错，外部网络所属的子网数不为1。");
				return floatingIpRange;
			}

			String subnetId = subnetIds.get(0);
			Subnet subnet = projectClientM3.networking().subnet().get(subnetId);
			if (null == subnet) {
				logger.error("获取浮动IP地址范围出错，外部网络所属的子网不存在。");
				return floatingIpRange;
			}

			// // prefix == 24
			// String cidr = subnet.getCidr();
			// int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
			// if (24 != prefix) {
			// logger.error("不支持非24的浮动IP掩码");
			// return floatingIpRange;
			// }

			List<? extends Pool> pools = subnet.getAllocationPools();
			if (0 == pools.size()) {
				logger.error("获取浮动IP地址范围出错，外部网络所属的子网没有指定地址池。");
				return floatingIpRange;
			}

			for (Pool pool : pools) {
				/*
				 * each allocation pool must be defined in one C class net.
				 */
				String startIpAddress = pool.getStart();
				String endIpAddress = pool.getEnd();

				// int CClassIpLength = 24;
				int lastDotPosition = startIpAddress.lastIndexOf('.');
				String startIpAddressCPrefix = startIpAddress.substring(0, lastDotPosition);

				lastDotPosition = endIpAddress.lastIndexOf('.');
				String endIpAddressCPrefix = endIpAddress.substring(0, lastDotPosition);

				// String startIpAddressCPrefix = startIpAddress.substring(0, CClassIpLength);
				// String endIpAddressCPrefix = endIpAddress.substring(0, CClassIpLength);
				if (false == startIpAddressCPrefix.equalsIgnoreCase(endIpAddressCPrefix)) {
					logger.error("获取浮动IP地址范围出错，外部网络所属的子网地址池不是C类网段，该子网被忽略。");

					continue;
				}

				// prefix of the start and end is the same, use any one.
				String addressPrefix = startIpAddressCPrefix;
				int start = Integer.parseInt((startIpAddress.substring(lastDotPosition + 1)));
				int end = Integer.parseInt((endIpAddress.substring(lastDotPosition + 1)));
				for (int i = start; i <= end; i++) {
					floatingIpRange.add(addressPrefix + "." + i);
				}
			}

			// // TODO assert all allocation pools have the same prefix
			// String ipSegment = pools.get(0).getStart().substring(0, pools.get(0).getStart().lastIndexOf('.'));
			// for (Pool p : pools) {
			// int start = Integer.parseInt(p.getStart().substring(p.getStart().lastIndexOf('.') + 1));
			// int end = Integer.parseInt(p.getEnd().substring(p.getEnd().lastIndexOf('.') + 1));
			// for (int i = start; i <= end; i++) {
			// floatingIpRange.add(ipSegment + "." + i);
			// }
			// }

			return floatingIpRange;
		} catch (NumberFormatException e) {
			throw new CloudException("获取外网地址空间发生错误。", e);
		} catch (Exception e) {
			throw new CloudException("获取外网地址空间发生错误。", e);
		}
	}

	@Override
	public List<? extends Port> getGatewayPorts() {
		try {
			List<? extends Port> gatewayPorts = projectClientM3.networking().port()
					.list(PortListOptions.create().networkId(PUBLIC_NETWORK_ID).deviceOwner("network:router_gateway"));
			return gatewayPorts;
		} catch (Exception e) {
			throw new CloudException("获取路由网关端口发生错误。", e);
		}
	}

	@Override
	public List<? extends Port> getFloatingIpPorts() {
		try {
			List<? extends Port> floatingIpPorts = projectClientM3.networking().port()
					.list(PortListOptions.create().networkId(PUBLIC_NETWORK_ID).deviceOwner("network:floatingip"));
			return floatingIpPorts;
		} catch (Exception e) {
			throw new CloudException("获取浮动IP端口发生错误。", e);
		}
	}

	@Override
	public List<? extends NetFloatingIP> getFloatingIps() {
		try {
			List<? extends NetFloatingIP> floatingIPs = projectClientM3.networking().floatingip().list();
			return floatingIPs;
		} catch (Exception e) {
			throw new CloudException("获取浮动IP列表发生错误。", e);
		}
	}

	@Override
	public Port getPort(String portId) {
		try {
			Port port = projectClientM3.networking().port().get(portId);
			return port;
		} catch (Exception e) {
			throw new CloudException("获取端口发生错误。", e);
		}
	}

	@Override
	public Router getRouter(String routerId) {
		try {
			Router router = projectClientM3.networking().router().get(routerId);
			return router;
		} catch (Exception e) {
			throw new CloudException("获取路由发生错误。", e);
		}
	}

	@Override
	public ActionResult createFloatingIp(String ipAddress, String serverId) {
		ActionResult result = new ActionResult();
		boolean success = false;
		String message = "";

		NetFloatingIP floatingIp;
		try {
			Server server = getServer(serverId);
			if (null == server) {
				success = false;
				message = "绑定地址失败，服务器不存在。";
				logger.error(message);
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}

			/*
			 * not possible to create and then associate using openstack4j, so create it with the port id.
			 */
			NeutronFloatingIP fip = new NeutronFloatingIP();
			fip.setFloatingIpAddress(ipAddress);
			fip.setTenantId(server.getTenantId());
			fip.setFloatingNetworkId(PUBLIC_NETWORK_ID);

			List<? extends Port> ports = projectClientM3.networking().port()
					.list(PortListOptions.create().deviceId(serverId));
			if (1 != ports.size()) {
				success = false;
				message = "绑定地址失败，服务器端口数不为1。";
				logger.error(message);
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}

			Port port = ports.get(0);
			String portId = port.getId();
			fip.setPortId(portId);
			floatingIp = projectClientM3.networking().floatingip().create(fip);

			success = true;
			message = floatingIp.getId();
			result.setSuccess(success);
			result.setMessage(message);
			return result;
		} catch (Exception e) {
			throw new CloudException("创建地址发生错误。", e);
		}
	}

	@Override
	public ActionResult deleteFloatingIp(String ipAddress) {
		ActionResult result = new ActionResult();
		boolean success = false;
		String message = "";

		try {
			List<? extends NetFloatingIP> floatingIps = getFloatingIps();

			for (NetFloatingIP floatingIp : floatingIps) {
				String floatingIpAddress = floatingIp.getFloatingIpAddress();
				if (true == ipAddress.equalsIgnoreCase(floatingIpAddress)) {
					ActionResponse response = projectClientM3.networking().floatingip().delete(floatingIp.getId());
					if (true == response.isSuccess()) {
						success = true;
						message = "";
					} else {
						success = false;
						message = response.getFault();
					}

					break;
				}
			}
		} catch (Exception e) {
			throw new CloudException("删除地址发生错误。", e);
		}

		result.setSuccess(success);
		result.setMessage(message);
		return result;
	}
	
}