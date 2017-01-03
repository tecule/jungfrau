package com.sinosoft.openstack;

import com.sinosoft.openstack.exception.CloudException;
import com.sinosoft.openstack.type.CloudConfig;

public class CloudManipulatorFactory {
	/**
	 * get a connected cloud client to the specified project
	 * 
	 * @param config
	 *            - cloud configuration
	 * @param projectId
	 *            - id of the project to connect
	 * @return cloud client
	 */
	public static CloudManipulator createCloudManipulator(CloudConfig config, String projectId) {
		if (config.getCloudManipulatorVersion().equalsIgnoreCase("v2")) {
			return new CloudManipulatorM2(config, projectId);
		} else if (config.getCloudManipulatorVersion().equalsIgnoreCase("v3")) {
			return new CloudManipulatorM3(config, projectId);
		} else {
			throw new CloudException("不支持当前的云服务配置。");
		}
	}
}
