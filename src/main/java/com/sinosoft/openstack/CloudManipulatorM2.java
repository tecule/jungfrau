package com.sinosoft.openstack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient.OSClientV2;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.types.Facing;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.IPProtocol;
import org.openstack4j.model.compute.QuotaSet;
import org.openstack4j.model.compute.SecGroupExtension;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.identity.v2.Role;
import org.openstack4j.model.identity.v2.Tenant;
import org.openstack4j.model.identity.v2.User;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.AttachInterfaceType;
import org.openstack4j.model.network.IPVersionType;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.RouterInterface;
import org.openstack4j.model.network.Subnet;
import org.openstack4j.model.telemetry.Alarm;
import org.openstack4j.model.telemetry.Alarm.ThresholdRule;
import org.openstack4j.openstack.OSFactory;
import org.openstack4j.openstack.telemetry.domain.CeilometerAlarm;
import org.openstack4j.openstack.telemetry.domain.CeilometerAlarm.CeilometerQuery;
import org.openstack4j.openstack.telemetry.domain.CeilometerAlarm.CeilometerThresholdRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sinosoft.openstack.exception.CloudException;
import com.sinosoft.openstack.type.ActionResult;
import com.sinosoft.openstack.type.CloudConfig;

public class CloudManipulatorM2 extends CloudManipulatorM {
	private static Logger logger = LoggerFactory.getLogger(CloudManipulatorM2.class);

	private String OS_AUTH_URL;
	private String OS_USERNAME;
	private String OS_PASSWORD;
	private String OS_TENANT_NAME;
	private int ALARM_THRESHOLD_RULE_PERIOD;

	public CloudManipulatorM2(CloudConfig appConfig, String projectId) {
		// TODO check if appConfig has valid data
		
		OS_AUTH_URL = appConfig.getAuthUrl();
		OS_USERNAME = appConfig.getAdminUsername();
		OS_PASSWORD = appConfig.getAdminPassword();
		OS_TENANT_NAME = appConfig.getAdminProjectName();
		PUBLIC_NETWORK_ID = appConfig.getPublicNetworkId();
		
		ALARM_THRESHOLD_RULE_PERIOD = appConfig.getAlarmThresholdRulePeriod();

		this.projectId = projectId;
		projectClientM2 = OSFactory.builderV2().endpoint(OS_AUTH_URL).credentials(OS_USERNAME, OS_PASSWORD)
				.tenantId(projectId).authenticate();

		projectClient = projectClientM2;
	}

	@Override
	public String createProject(String projectName, String projectDescription, int instanceQuota, int cpuQuota,
			int memoryQuota) {
		String tenantId;

		try {
			OSClientV2 client = OSFactory.builderV2().endpoint(OS_AUTH_URL).credentials(OS_USERNAME, OS_PASSWORD)
					.tenantName(OS_TENANT_NAME).perspective(Facing.ADMIN).authenticate();

			// create tenant
			Tenant tenant = client.identity().tenants().create(Builders.identityV2().tenant()
					.name(projectName + "_" + UUID.randomUUID().toString()).description(projectDescription).build());
			tenantId = tenant.getId();

			// set quota
			client.compute().quotaSets().updateForTenant(tenantId,
					Builders.quotaSet().instances(instanceQuota).cores(cpuQuota).ram(memoryQuota * 1024).build());

			// set user permission
			User adminUser = client.identity().users().getByName(OS_USERNAME);
			Role adminRole = client.identity().roles().getByName("admin");
			ActionResponse response = client.identity().roles().addUserRole(tenantId, adminUser.getId(),
					adminRole.getId());
			if (false == response.isSuccess()) {
				logger.error(response.getFault());
			}

			// build network and router
			Network network = client.networking().network()
					.create(Builders.network().name("private").tenantId(tenantId).adminStateUp(true).build());
			// .addDNSNameServer("124.16.136.254")
			Subnet subnet = client.networking().subnet()
					.create(Builders.subnet().name("private_subnet").networkId(network.getId()).tenantId(tenantId)
							.ipVersion(IPVersionType.V4).cidr("192.168.32.0/24").gateway("192.168.32.1")
							.enableDHCP(true).build());
			Router router = client.networking().router().create(Builders.router().name("router").adminStateUp(true)
					.externalGateway(PUBLIC_NETWORK_ID).tenantId(tenantId).build());
			@SuppressWarnings("unused")
			RouterInterface iface = client.networking().router().attachInterface(router.getId(),
					AttachInterfaceType.SUBNET, subnet.getId());

			// adjust security group
			OSClientV2 tenantClient = OSFactory.builderV2().endpoint(OS_AUTH_URL).credentials(OS_USERNAME, OS_PASSWORD)
					.tenantId(tenantId).authenticate();
			List<? extends SecGroupExtension> secGroups = tenantClient.compute().securityGroups().list();
			for (SecGroupExtension secGroup : secGroups) {
				tenantClient.compute().securityGroups().createRule(Builders.secGroupRule().cidr("0.0.0.0/0")
						.parentGroupId(secGroup.getId()).protocol(IPProtocol.ICMP).range(-1, -1).build());
				tenantClient.compute().securityGroups().createRule(Builders.secGroupRule().cidr("0.0.0.0/0")
						.parentGroupId(secGroup.getId()).protocol(IPProtocol.TCP).range(1, 65535).build());
				tenantClient.compute().securityGroups().createRule(Builders.secGroupRule().cidr("0.0.0.0/0")
						.parentGroupId(secGroup.getId()).protocol(IPProtocol.UDP).range(1, 65535).build());
			}
		} catch (AuthenticationException e) {
			throw new CloudException("创建项目发生错误，项目ID：" + projectId + "。", e);
		}

		return tenantId;
	}

	@Override
	public QuotaSet updateComputeServiceQuota(int instanceQuota, int cpuQuota, int memoryQuota) {
		try {
			OSClientV2 client = OSFactory.builderV2().endpoint(OS_AUTH_URL).credentials(OS_USERNAME, OS_PASSWORD)
					.tenantId(projectId).perspective(Facing.ADMIN).authenticate();

			QuotaSet quota = client.compute().quotaSets().updateForTenant(projectId,
					Builders.quotaSet().cores(cpuQuota).instances(instanceQuota).ram(memoryQuota * 1024).build());

			return quota;
		} catch (AuthenticationException e) {
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
			OSClientV2 client = OSFactory.builderV2().endpoint(OS_AUTH_URL).credentials(OS_USERNAME, OS_PASSWORD)
					.tenantId(projectId).perspective(Facing.ADMIN).authenticate();

			// check if this tenant has vm
			List<? extends Server> servers = client.compute().servers().list();
			if (servers.size() > 0) {
				success = false;
				message = "删除项目发生错误，当前项目包含虚拟机，不允许删除";
				logger.error(message);
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}

			// get internal subnet
			List<Network> tenantNetworks = new ArrayList<Network>();
			List<Subnet> tenantSubnets = new ArrayList<Subnet>();
			List<? extends Network> networks = client.networking().network().list();
			for (Network network : networks) {
				if (network.getTenantId().equalsIgnoreCase(projectId)) {
					tenantNetworks.add(network);
					tenantSubnets.addAll(network.getNeutronSubnets());
				}
			}

			// delete router
			List<? extends Router> routers = client.networking().router().list();
			for (Router router : routers) {
				if (router.getTenantId().equalsIgnoreCase(projectId)) {
					// detach from internal network
					for (Subnet subnet : tenantSubnets) {
						client.networking().router().detachInterface(router.getId(), subnet.getId(), null);
					}

					response = client.networking().router().delete(router.getId());
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

			// delete tenant network
			for (Network network : tenantNetworks) {
				response = client.networking().network().delete(network.getId());
				if (response.isSuccess() == false) {
					success = false;
					message = "删除项目发生错误，删除网络失败。";
					logger.error(message + response.getFault());
					result.setSuccess(success);
					result.setMessage(message);
					return result;
				}
			}

			// delete tenant
			response = client.identity().tenants().delete(projectId);
			if (response.isSuccess() == false) {
				success = false;
				message = "删除项目发生错误，删除项目失败。";
				logger.error(message + response.getFault());
				result.setSuccess(success);
				result.setMessage(message);
				return result;
			}
		} catch (AuthenticationException e) {
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
		Image image = getImage(imageId);
		Image newImage = projectClientM2.images().update(image.toBuilder().name(imageName).isPublic(publicity).build());
		return newImage;
	}

	@Override
	public String createAlarm(String serverId, String alarmName, String meterName, float threshold) {
		// get resource id
		String resourceId = getResourceId(serverId, meterName);
		if (null == resourceId) {
			return null;
		}

		CeilometerQuery query = new CeilometerQuery();
		query.setField("resource_id");
		query.setOp(Alarm.ThresholdRule.ComparisonOperator.EQ);
		query.setValue(resourceId);
		List<CeilometerQuery> queries = new ArrayList<CeilometerAlarm.CeilometerQuery>();
		queries.add(query);

		CeilometerThresholdRule rule = new CeilometerThresholdRule();
		rule.setMeterName(meterName);
		rule.setThreshold(threshold);
		rule.setComparisonOperator(Alarm.ThresholdRule.ComparisonOperator.GE);
		rule.setStatistic(Alarm.ThresholdRule.Statistic.AVG);
		rule.setPeriod(ALARM_THRESHOLD_RULE_PERIOD);
		rule.setEvaluationPeriods(1);
		rule.setQuery(queries);

		List<String> actions = new ArrayList<String>();
		actions.add("log://");

		// alarm name must be unique inside the tenant, better suffix with the instance id
		Alarm alarm = projectClientM2.telemetry().alarms()
				.create(Builders.alarm().name(alarmName + "@" + serverId).description(alarmName + " high")
						.type(Alarm.Type.THRESHOLD).thresholeRule(rule).alarmActions(actions).isEnabled(true).build());

		return alarm.getAlarmId();
	}

	@Override
	public boolean updateAlarm(String alarmId, boolean enabled, float threshold) {
		Alarm alarm = projectClientM2.telemetry().alarms().getById(alarmId);
		alarm.isEnabled(enabled);

		ThresholdRule modifiedRule = alarm.getThresholdRule();
		modifiedRule.setThreshold(threshold);
		alarm.setThresholdRule((CeilometerThresholdRule) modifiedRule);

		projectClientM2.telemetry().alarms().update(alarm.getAlarmId(), alarm);

		// TODO: verify if this works
		// tenantClient.telemetry().alarms().update(alarm.getAlarmId(),
		// alarm.toBuilder().isEnabled(enabled).thresholeRule((CeilometerThresholdRule) modifiedRule).build());

		return true;
	}

	@Override
	public boolean deleteAlarm(String alarmId) {
		ActionResponse response = projectClientM2.telemetry().alarms().delete(alarmId);
		if (false == response.isSuccess()) {
			logger.error(response.getFault());
			return false;
		}

		return true;
	}

	@Override
	public String getAlarmState(String alarmId) {
		Alarm alarm = projectClientM2.telemetry().alarms().getById(alarmId);
		return alarm.getState();
	}
}
