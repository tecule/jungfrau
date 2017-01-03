package com.sinosoft.openstack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.MediaType;

import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient.OSClientV3;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.IPProtocol;
import org.openstack4j.model.compute.QuotaSet;
import org.openstack4j.model.compute.SecGroupExtension;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.identity.v3.Project;
import org.openstack4j.model.identity.v3.Role;
import org.openstack4j.model.identity.v3.User;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.AttachInterfaceType;
import org.openstack4j.model.network.IPVersionType;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.RouterInterface;
import org.openstack4j.model.network.Subnet;
import org.openstack4j.openstack.OSFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sinosoft.openstack.exception.CloudException;
import com.sinosoft.openstack.type.ActionResult;
import com.sinosoft.openstack.type.CloudConfig;
import com.sinosoft.openstack.type.telemetry.Alarm;
import com.sinosoft.openstack.type.telemetry.Query;
import com.sinosoft.openstack.type.telemetry.ThresholdRule;

public class CloudManipulatorM3 extends CloudManipulatorM {
	private static Logger logger = LoggerFactory.getLogger(CloudManipulatorM3.class);

	// appConfig cannot be @Autowired, since it's been used in the constructor, and @Autowired is done after
	// construction

	private String OS_AUTH_URL;
	private String OS_USER_DOMAIN_NAME;
	private String OS_USER_DOMAIN_ID;
	private String OS_USERNAME;
	private String OS_USER_ID;
	private String OS_PASSWORD;
	private String OS_ROLE_NAME;
	private String AODH_SERVICE_URL;

	public CloudManipulatorM3(CloudConfig appConfig, String projectId) {
		OS_AUTH_URL = appConfig.getAuthUrl();
		OS_USER_DOMAIN_NAME = appConfig.getDomainName();
		OS_USER_DOMAIN_ID = appConfig.getDomainId();
		OS_USERNAME = appConfig.getAdminUsername();
		OS_USER_ID = appConfig.getAdminUserId();
		OS_PASSWORD = appConfig.getAdminPassword();
		OS_ROLE_NAME = appConfig.getAdminRoleName();
		AODH_SERVICE_URL = appConfig.getAodhServiceUrl();
		PUBLIC_NETWORK_ID = appConfig.getPublicNetworkId();

		this.projectId = projectId;
		try {
			projectClientM3 = OSFactory.builderV3().endpoint(OS_AUTH_URL)
					.credentials(OS_USERNAME, OS_PASSWORD, Identifier.byName(OS_USER_DOMAIN_NAME))
					.scopeToProject(Identifier.byId(projectId)).authenticate();			

			projectClient = projectClientM3;
		} catch (AuthenticationException e) {
			throw new CloudException("云服务认证发生错误。", e);
		} catch (Exception e) {
			throw new CloudException("创建云服务连接发生错误。", e);
		}
	}

	@Override
	public String createProject(String projectName, String projectDescription, int instanceQuota, int cpuQuota,
			int memoryQuota) {
		String projectId = "";

		try {
			OSClientV3 domainClient = OSFactory.builderV3().endpoint(OS_AUTH_URL).credentials(OS_USER_ID, OS_PASSWORD)
					.scopeToDomain(Identifier.byId(OS_USER_DOMAIN_ID)).authenticate();

			// create tenant
			Project project = domainClient
					.identity()
					.projects()
					.create(Builders.project().name(projectName + "_" + UUID.randomUUID().toString())
							.description(projectDescription).build());
			projectId = project.getId();

			// rename
			domainClient.identity().projects()
					.update(project.toBuilder().name(projectName + "_" + projectId.substring(0, 8)).build());

			// set user permission
			List<? extends User> users = domainClient.identity().users().getByName(OS_USERNAME);
			if (users.size() <= 0) {
				throw new CloudException("获取用户发生错误。");
			}
			User adminUser = users.get(0);
			List<? extends Role> roles = domainClient.identity().roles().getByName(OS_ROLE_NAME);
			if (roles.size() <= 0) {
				throw new CloudException("获取角色发生错误。");
			}
			Role adminRole = roles.get(0);
			ActionResponse response = domainClient.identity().roles()
					.grantProjectUserRole(projectId, adminUser.getId(), adminRole.getId());
			if (false == response.isSuccess()) {
				logger.error("设置角色发生错误。" + response.getFault());
				throw new CloudException("设置角色发生错误。");
			}

			// use project client for following operation
			OSClientV3 newProjectClient = OSFactory.builderV3().endpoint(OS_AUTH_URL)
					.credentials(OS_USERNAME, OS_PASSWORD, Identifier.byName(OS_USER_DOMAIN_NAME))
					.scopeToProject(Identifier.byId(projectId)).authenticate();

			// set quota
			newProjectClient
					.compute()
					.quotaSets()
					.updateForTenant(
							projectId,
							Builders.quotaSet().instances(instanceQuota).cores(cpuQuota).ram(memoryQuota * 1024)
									.build());

			// build network and router
			Network network = newProjectClient
					.networking()
					.network()
					.create(Builders.network().name("private" + "_" + projectId.substring(0, 8)).tenantId(projectId)
							.adminStateUp(true).build());
			// .addDNSNameServer("114.114.114.114")
			Subnet subnet = newProjectClient
					.networking()
					.subnet()
					.create(Builders.subnet().name("private_subnet" + "_" + projectId.substring(0, 8))
							.networkId(network.getId()).tenantId(projectId).ipVersion(IPVersionType.V4)
							.cidr("192.168.32.0/24").gateway("192.168.32.1").enableDHCP(true).build());
			Router router = newProjectClient
					.networking()
					.router()
					.create(Builders.router().name("router" + "_" + projectId.substring(0, 8)).adminStateUp(true)
							.externalGateway(PUBLIC_NETWORK_ID).tenantId(projectId).build());
			@SuppressWarnings("unused")
			RouterInterface iface = newProjectClient.networking().router()
					.attachInterface(router.getId(), AttachInterfaceType.SUBNET, subnet.getId());

			// add security group rule
			List<? extends SecGroupExtension> secGroups = newProjectClient.compute().securityGroups().list();
			for (SecGroupExtension secGroup : secGroups) {
				newProjectClient
						.compute()
						.securityGroups()
						.createRule(
								Builders.secGroupRule().cidr("0.0.0.0/0").parentGroupId(secGroup.getId())
										.protocol(IPProtocol.ICMP).range(-1, -1).build());
				newProjectClient
						.compute()
						.securityGroups()
						.createRule(
								Builders.secGroupRule().cidr("0.0.0.0/0").parentGroupId(secGroup.getId())
										.protocol(IPProtocol.TCP).range(1, 65535).build());
				newProjectClient
						.compute()
						.securityGroups()
						.createRule(
								Builders.secGroupRule().cidr("0.0.0.0/0").parentGroupId(secGroup.getId())
										.protocol(IPProtocol.UDP).range(1, 65535).build());
			}
		} catch (Exception e) {
			throw new CloudException("创建项目发生错误，项目ID：" + projectId + "。", e);
		}

		return projectId;
	}

	@Override
	public QuotaSet updateComputeServiceQuota(int instanceQuota, int cpuQuota, int memoryQuota) {
		try {
			QuotaSet quota = projectClientM3
					.compute()
					.quotaSets()
					.updateForTenant(
							projectId,
							Builders.quotaSet().cores(cpuQuota).instances(instanceQuota).ram(memoryQuota * 1024)
									.build());

			return quota;
		} catch (Exception e) {
			throw new CloudException("更新计算服务配额发生错误。", e);
		}
	}

	@Override
	public ActionResult deleteProject() {
		ActionResult result = new ActionResult();
		boolean success = false;
		String message = "";

		ActionResponse response;

		try {
			// check if this tenant has server
			List<? extends Server> servers = projectClientM3.compute().servers().list();
			if (servers.size() > 0) {
				success = false;
				message = "删除项目发生错误，当前项目包含虚拟机，不允许删除。";
				logger.error(message);
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}

			// get internal subnet
			List<Network> tenantNetworks = new ArrayList<Network>();
			List<Subnet> tenantSubnets = new ArrayList<Subnet>();
			List<? extends Network> networks = projectClientM3.networking().network().list();
			for (Network network : networks) {
				if (network.getTenantId().equalsIgnoreCase(projectId)) {
					tenantNetworks.add(network);
					tenantSubnets.addAll(network.getNeutronSubnets());
				}
			}

			// (1) delete router,
			List<? extends Router> routers = projectClientM3.networking().router().list();
			for (Router router : routers) {
				if (router.getTenantId().equalsIgnoreCase(projectId)) {
					// detach from internal network
					for (Subnet subnet : tenantSubnets) {
						projectClientM3.networking().router().detachInterface(router.getId(), subnet.getId(), null);
					}

					// delete "HA subnet tenant xxx" automatically
					response = projectClientM3.networking().router().delete(router.getId());
					if (response.isSuccess() == false) {
						success = false;
						message = "删除项目发生错误，删除路由器失败。";
						logger.error(message + response.getFault());
						result.setSuccess(success);
						result.setMessage(message);
						return result;
					}
				}
			}

			// (2) delete tenant network
			for (Network network : tenantNetworks) {
				response = projectClientM3.networking().network().delete(network.getId());
				if (response.isSuccess() == false) {
					success = false;
					message = "删除项目发生错误，删除网络失败。";
					logger.error(message + response.getFault());
					result.setSuccess(success);
					result.setMessage(message);
					return result;
				}
			}

			// (3) delete project
			response = projectClientM3.identity().projects().delete(projectId);
			if (response.isSuccess() == false) {
				success = false;
				message = "删除项目发生错误，删除项目失败。";
				logger.error(message + response.getFault());
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}
		} catch (Exception e) {
			success = false;
			message = "删除项目发生错误。";
			logger.error(message + e.getMessage());
			result.setSuccess(success);
			result.setMessage(message);
			return result;
		}

		success = true;
		message = "";
		result.setSuccess(success);
		result.setMessage(message);
		return result;
	}
	
	@Override
	public Image updateImage(String imageId, String imageName, boolean publicity) {
		// TODO image name with chinese character not work in "PUT /v1/images/xxxxxx"

		try {
			Image image = getImage(imageId);

			// Image newImage =
			// projectClient.images().update(image.toBuilder().name(imageName).isPublic(publicity).build());
			Image newImage = projectClientM3.images().update(image.toBuilder().isPublic(publicity).build());
			return newImage;
		} catch (Exception e) {
			throw new CloudException("更新镜像发生错误。", e);
		}
	}

	@Override
	public String createAlarm(String serverId, String alarmName, String meterName, float threshold) {
		// get resource id
		String resourceId = getResourceId(serverId, meterName);
		if (null == resourceId) {
			throw new CloudException("创建虚拟机告警发生错误，无效的资源类型。");
		}

		Alarm alarm = new Alarm();
		// alarm name must be unique inside the tenant, better suffix with the instance id
		alarm.setName(alarmName + "@" + serverId);
		alarm.setDescription(alarmName + " high");
		alarm.setEnabled(true);
		alarm.setType("threshold");
		alarm.setSeverity("low");
		alarm.setState("insufficient data");

		ThresholdRule rule = new ThresholdRule();
		rule.setMeterName(meterName);
		rule.setComparisonOperator("ge");
		rule.setThreshold(threshold);
		rule.setStatistic("avg");
		rule.setPeriod(60);
		rule.setEvaluationPeriods(1);

		Query query = new Query();
		query.setField("resource_id");
		query.setOp("eq");
		query.setValue(resourceId);
		List<Query> queries = new ArrayList<Query>();
		queries.add(query);
		rule.setQuery(queries);
		alarm.setThresholdRule(rule);

		List<String> actions = new ArrayList<String>();
		actions.add("log://");
		alarm.setAlarmActions(actions);

		try {
			// use resteasy client if reasteasy-xxx-2.x is used
			ClientRequest request = new ClientRequest(AODH_SERVICE_URL);
			request.header("X-Auth-Token", projectClientM3.getToken().getId());

			/*
			 * post alarm object raise RuntimeException, post the JSON string is ok: java.lang.RuntimeException: could
			 * not find writer for content-type application/json type
			 */
			// request.body(MediaType.APPLICATION_JSON, alarm);
			ObjectMapper mapper = new ObjectMapper();
			String alarmJson = mapper.writeValueAsString(alarm);
			request.body(MediaType.APPLICATION_JSON, alarmJson);

			ClientResponse<String> response = request.post(String.class);
			int responseCode = response.getResponseStatus().getStatusCode();
			String responseContent = response.getEntity();
			logger.trace("responseCode: " + responseCode + ", responseContent：" + responseContent);
			if (201 == responseCode) {
				Alarm createdAlarm = mapper.readValue(responseContent, Alarm.class);
				return createdAlarm.getAlarmId();
			} else {
				String message = "创建虚拟机告警失败，虚拟机ID：" + serverId + ", 监控指标：" + meterName + "。返回代码: " + responseCode;
				throw new CloudException(message);
			}
		} catch (Exception e) {
			throw new CloudException("创建虚拟机告警发生错误。", e);
		}
	}

	/**
	 * get alarm by id.
	 * 
	 * @param alarmId
	 *            - alarm id
	 * @return alarm with the given id, or null if not found
	 * @author xiangqian
	 */
	private Alarm getAlarm(String alarmId) {
		try {
			ClientRequest request = new ClientRequest(AODH_SERVICE_URL + "/" + alarmId);
			request.header("X-Auth-Token", projectClientM3.getToken().getId());
			ClientResponse<String> response = request.get(String.class);

			int responseCode = response.getResponseStatus().getStatusCode();
			String responseContent = response.getEntity();
			logger.trace("responseCode: " + responseCode + ", responseContent：" + responseContent);
			if (200 == responseCode) {
				ObjectMapper mapper = new ObjectMapper();
				Alarm alarm = mapper.readValue(responseContent, Alarm.class);
				return alarm;
			} else {
				logger.error("获取虚拟机告警失败，告警ID：" + alarmId + "。response code: " + responseCode + ", responseContent："
						+ responseContent);
				return null;
			}
		} catch (Exception e) {
			throw new CloudException("获取虚拟机告警发生错误。", e);
		}
	}

	@Override
	public boolean updateAlarm(String alarmId, boolean enabled, float threshold) {
		try {
			Alarm alarm = getAlarm(alarmId);
			if (null == alarm) {
				String message = "更新虚拟机告警失败，告警不存在，告警ID：" + alarmId;
				logger.error(message);
				return false;
			}

			alarm.setEnabled(enabled);

			ThresholdRule rule = alarm.getThresholdRule();
			rule.setThreshold(threshold);

			alarm.setThresholdRule(rule);

			ClientRequest request = new ClientRequest(AODH_SERVICE_URL + "/" + alarmId);
			request.header("X-Auth-Token", projectClientM3.getToken().getId());

			ObjectMapper mapper = new ObjectMapper();
			mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
			mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

			String alarmJson = mapper.writeValueAsString(alarm);
			request.body(MediaType.APPLICATION_JSON, alarmJson);
			ClientResponse<String> response = request.put(String.class);

			int responseCode = response.getResponseStatus().getStatusCode();
			String responseContent = response.getEntity();
			logger.trace("返回代码: " + responseCode + ", 返回内容：" + responseContent);
			if (200 == responseCode) {
				return true;
			} else {
				String message = "更新虚拟机告警失败，告警ID：" + alarmId + "。返回代码: " + responseCode + ", 返回内容：" + responseContent;
				logger.error(message);
				return false;
			}
		} catch (Exception e) {
			throw new CloudException("更新虚拟机告警发生错误。", e);
		}
	}

	@Override
	public boolean deleteAlarm(String alarmId) {
		try {
			ClientRequest request = new ClientRequest(AODH_SERVICE_URL + "/" + alarmId);
			request.header("X-Auth-Token", projectClientM3.getToken().getId());
			ClientResponse<String> response = request.delete(String.class);

			int responseCode = response.getResponseStatus().getStatusCode();
			String responseContent = response.getEntity();
			logger.trace("responseCode: " + responseCode + ", responseContent：" + responseContent);
			if (204 == responseCode) {
				return true;
			} else {
				String message = "删除虚拟机告警失败，告警ID：" + alarmId + "。返回代码: " + responseCode + ", 返回内容：" + responseContent;
				logger.error(message);
				return false;
			}
		} catch (Exception e) {
			throw new CloudException("删除虚拟机告警发生错误。" + e.getMessage(), e);
		}
	}

	@Override
	public String getAlarmState(String alarmId) {
		Alarm alarm = getAlarm(alarmId);
		if (null != alarm) {
			return alarm.getState();
		}

		return null;
	}
}
