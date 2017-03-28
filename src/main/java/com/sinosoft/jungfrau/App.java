package com.sinosoft.jungfrau;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.joda.time.DateTime;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.OSClient.OSClientV2;
import org.openstack4j.api.OSClient.OSClientV3;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.actions.LiveMigrateOptions;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.ExternalGateway;
import org.openstack4j.model.network.IP;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Port;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.options.PortListOptions;
import org.openstack4j.model.storage.block.BlockLimits.Absolute;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.telemetry.MeterSample;
import org.openstack4j.model.telemetry.SampleCriteria;
import org.openstack4j.openstack.OSFactory;
import org.openstack4j.openstack.networking.domain.NeutronFloatingIP;

import com.sinosoft.openstack.CloudManipulator;
import com.sinosoft.openstack.CloudManipulatorFactory;
import com.sinosoft.openstack.type.CloudConfig;

/**
 * Hello world!
 *
 */
public class App {
  static OSClientV3 projectClientV3;
  static OSClientV2 projectClientV2;
  static OSClient projectClient;

  public static void main(String[] args) {
    // projectClientV3 = OSFactory.builderV3().endpoint("http://192.168.101.151:5000/v3")
    // .credentials("admin", "123456", Identifier.byName("default"))
    // .scopeToProject(Identifier.byId("3eff824e94eb477889925ee6275a08ed")).authenticate();

    // projectClientV2 =
    // OSFactory.builderV2().endpoint("http://192.168.100.11:5000/v2.0").credentials("admin",
    // "631a1ae0e8964e3c")
    // .tenantId("8ba71bd972aa42339d81184045d99dcd").authenticate();

    // projectClient = projectClientV3;
    // System.out.println(projectClient.compute().hypervisors().list().size());
    // System.out.println(projectClientV3.compute().hypervisors().list().size());

    // projectClient = projectClientV2;
    // System.out.println(projectClient.compute().hypervisors().list().size());
    // System.out.println(projectClientV2.compute().hypervisors().list().size());

    // try {
    // showSamples();
    // } catch (ParseException e) {
    // // TODO Auto-generated catch block
    // e.printStackTrace();
    // }

    // showImages();

    CloudConfig config = new CloudConfig();
    // config.setCloudManipulatorVersion("v3");
    // config.setAuthUrl("http://192.168.101.151:5000/v3");
    // config.setAdminUsername("admin");
    // config.setAdminPassword("123456");
    // config.setAdminProjectName("");
    // config.setPublicNetworkId("01f12475-3687-4214-9d6d-8f2d46447eb1");
    // config.setAdminUserId("20de005915c54ee989f3b95da7de5a35");
    // config.setDomainName("default");
    // config.setDomainId("7e4ad5d50d344a3498184e9bd44d7976");
    // config.setAdminProjectId("3eff824e94eb477889925ee6275a08ed");
    // config.setAdminRoleName("admin");
    // config.setAodhServiceUrl("http://192.168.101.151:8042/v2/alarms");

    config.setCloudManipulatorVersion("v2");
    config.setAuthUrl("http://192.168.100.11:5000/v2.0");
    config.setAdminUsername("admin");
    config.setAdminPassword("631a1ae0e8964e3c");
    config.setAdminProjectName("admin");
    config.setPublicNetworkId("a88b15e4-6d04-4960-aafa-db783b8964b8");
    config.setAdminUserId("");
    config.setDomainName("");
    config.setDomainId("");
    config.setAdminProjectId("8ba71bd972aa42339d81184045d99dcd");
    config.setAdminRoleName("");
    config.setAodhServiceUrl("");

    CloudManipulator cloud = CloudManipulatorFactory.createCloudManipulator(config,
        config.getAdminProjectId());
    // String projectId = cloud.createProject("project-v2", "v2", 1, 1, 1);
    // System.out.println(projectId);

    // cloud.updateComputeServiceQuota(2, 2, 2);

    // Absolute absolute = cloud.getBlockStorageQuotaUsage();
    // System.out.println(absolute.getTotalGigabytesUsed());

    cloud.getExternalIps();
    System.out.println("finished");
  }

  private static void showFloatingIps() {
    List<? extends NetFloatingIP> flotingIps = projectClientV3.networking().floatingip().list();
    for (NetFloatingIP floatingIp : flotingIps) {
      System.out.println("---");
      System.out.println(floatingIp.getId());
      System.out.println(floatingIp.getPortId());
      if (null != floatingIp.getPortId()) {
        System.out.println("------");
        Port port = projectClientV3.networking().port().get(floatingIp.getPortId());
        // instance id
        System.out.println(port.getDeviceId());
        System.out.println(port.getDeviceOwner());
      } else {
        // floating ip not associated to any instance.
      }
      System.out.println(floatingIp.getTenantId());
      System.out.println(floatingIp.getRouterId());
      System.out.println(floatingIp.getFloatingNetworkId());
      System.out.println(floatingIp.getFloatingIpAddress());
      System.out.println(floatingIp.getFixedIpAddress());
    }
  }

  private static void showRouters() {
    List<? extends Router> routers = projectClientV3.networking().router().list();
    for (Router router : routers) {
      System.out.println("---");
      System.out.println(router.getId());
      System.out.println(router.getTenantId());
      System.out.println(router.getName());
      ExternalGateway gateway = router.getExternalGatewayInfo();
      System.out.println(gateway.getNetworkId());
    }
  }

  private static void showFloatingIpPorts() {
    List<? extends Port> ports = projectClientV3.networking().port().list(PortListOptions.create()
        .networkId("01f12475-3687-4214-9d6d-8f2d46447eb1").deviceOwner("network:floatingip"));
    for (Port port : ports) {
      System.out.println("------");
      System.out.println(port.getId());

      // floatingip id
      System.out.println(port.getDeviceId());
      NetFloatingIP floatingIp = projectClientV3.networking().floatingip().get(port.getDeviceId());
      String portId = floatingIp.getPortId();
      if (null != portId) {
        Port instancePort = projectClientV3.networking().port().get(portId);
        // instance id
        System.out.println("associated instance id: " + instancePort.getDeviceId());
      } else {
        // not associated to instance
      }

      System.out.println(port.getDeviceOwner());

      Set<? extends IP> fixedIps = port.getFixedIps();
      for (IP fixedIp : fixedIps) {
        System.out.println(fixedIp.getIpAddress());
      }
    }
  }

  private static void showRouterGatewayPorts() {
    List<? extends Port> ports = projectClientV3.networking().port().list(PortListOptions.create()
        .networkId("01f12475-3687-4214-9d6d-8f2d46447eb1").deviceOwner("network:router_gateway"));
    for (Port port : ports) {
      System.out.println("------");
      System.out.println(port.getId());

      // router id
      System.out.println(port.getDeviceId());

      System.out.println(port.getDeviceOwner());

      Set<? extends IP> fixedIps = port.getFixedIps();
      for (IP fixedIp : fixedIps) {
        System.out.println(fixedIp.getIpAddress());
      }
    }
  }

  private static void CreateAndRemoveFloatingIp() {
    NeutronFloatingIP fip = new NeutronFloatingIP();
    fip.setFloatingIpAddress("192.168.101.172");
    fip.setTenantId("269a7f333a114e7eb7b95e6ceb993d93");
    fip.setFloatingNetworkId("01f12475-3687-4214-9d6d-8f2d46447eb1");
    // NetFloatingIP floatingIp = projectClient.networking().floatingip().create(fip);
    // String floatingIpId = floatingIp.getId();
    // System.out.println(floatingIpId);

    List<? extends Port> ports = projectClientV3.networking().port()
        .list(PortListOptions.create().deviceId("275bc735-5ce6-4bc7-a260-92be5de7efad"));
    Port port = ports.get(0);
    String portId = port.getId();
    System.out.println(portId);

    // fip.setPortId(portId);
    // NetFloatingIP floatingIp = projectClient.networking().floatingip().create(fip);
    // String floatingIpId = floatingIp.getId();
    // System.out.println(floatingIpId);

    // projectClient.networking().floatingip().associateToPort(floatingIpId, portId);

    // projectClient.networking().floatingip().disassociateFromPort("482a8862-f332-4672-8af2-d5f89ca0481b");

    ActionResponse response = projectClientV3.networking().floatingip()
        .delete("482a8862-f332-4672-8af2-d5f89ca0481b");
    System.out.println(response.isSuccess());
  }

  private static void liveMigrate() {
    ActionResponse response = projectClientV3.compute().servers()
        .liveMigrate("dc71143c-b0dd-4e1f-bf8e-ed524d3dd5de", LiveMigrateOptions.create().host(""));
    if (false == response.isSuccess()) {
      System.out.println(response.getFault());
    } else {
      System.out.println("instance migrated");
    }
  }

  private static void createServer() {
    String serverName = "测试虚拟机01";
    String flavorId = "1";
    String imageId = "91114430-6ddc-4b97-a160-528500ab6b2c";

    Server server = projectClientV3.compute().servers()
        .boot(Builders.server().name(serverName).flavor(flavorId).image(imageId).build());

    System.out.println("server created: " + server.getId());

    return;
  }

  private static void createVolume() {
    String volumeName = "测试磁盘01";
    String volumeDescription = "test02";

    Volume v = projectClientV3.blockStorage().volumes()
        .create(Builders.volume().name(volumeName).size(1).description(volumeDescription).build());

    System.out.println("volume created: " + v.getId());

    return;
  }

  private static void createSnapshot() {
    String serverId = "f8ec8e6f-0662-499f-8524-1ab8e845c827";
    String snapshotName = "测试快照01";

    String snapshotId = projectClientV3.compute().servers().createSnapshot(serverId, snapshotName);

    System.out.println("snapshot created: " + snapshotId);

    return;
  }

  private static String escapeNonAscii(String inputString) {
    /*
     * The characters with values that are outside of the 16-bit range, and within the range from
     * 0x10000 to 0x10FFFF, are called supplementary characters and are defined as a pair of char
     * values. http://stackoverflow.com/questions/5733931/java-string-unicode-value
     */
    StringBuilder unicodeFormat = new StringBuilder();
    for (int i = 0; i < inputString.length(); i++) {
      int cp = Character.codePointAt(inputString, i);
      int charCount = Character.charCount(cp);
      if (charCount > 1) {
        i += charCount - 1; // 2.
        if (i >= inputString.length()) {
          throw new IllegalArgumentException("truncated unexpectedly");
        }
      }

      if (cp < 128) {
        unicodeFormat.appendCodePoint(cp);
      } else {
        unicodeFormat.append(String.format("\\u%x", cp));
      }
    }

    return unicodeFormat.toString();
  }

  private static void showSamples() throws ParseException {
    // in milliseconds
    long sampleInterval = 10 * 60 * 1000;
    String resourceId = "b182c26c-6edb-4183-b1f9-058d9f7edfff";
    long timestamp = System.currentTimeMillis() - (60 * sampleInterval);
    String meterName = "cpu_util";

    SampleCriteria criteria = new SampleCriteria().resource(resourceId)
        .timestamp(SampleCriteria.Oper.GT, timestamp);
    List<? extends MeterSample> meterSamples = projectClientV3.telemetry().meters()
        .samples(meterName, criteria);
    for (MeterSample sample : meterSamples) {
      System.out.print(sample.getTimestamp() + "\t");
      System.out.print(sample.getCounterVolume() + "\t");

      DateTime dt = new DateTime(sample.getTimestamp());
      System.out.print(dt.toString() + "\t");

      SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
      Date d = formatter.parse(sample.getTimestamp());
      formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      System.out.print(formatter.format(d) + "\t");

      System.out.println();
    }

    System.out.println("samples end");
  }

  private static void showImages() {
    List<? extends Image> images = projectClientV3.images().list();
    for (Image image : images) {
      System.out.println("image name: " + image.getName());
    }

    System.out.println("images end");
  }

  private static void showImage() {
    /*
     * name with Chinese characters NOT support
     */
    String imageId = "74323558-7af7-4f08-ba54-cbe5e32dd499";
    Image image = projectClientV3.images().get(imageId);
    System.out.println("image name: " + image.getName());
  }

  private static void showImage2() {
    String GLANCE_PUBLIC_URL = "http://192.168.101.151:9292";
    String imageId = "74323558-7af7-4f08-ba54-cbe5e32dd499";

    ClientRequest request = new ClientRequest(GLANCE_PUBLIC_URL + "/v2/images/" + imageId);
    request.header("X-Auth-Token", projectClientV3.getToken().getId());
    ClientResponse<String> response;
    try {
      response = request.get(String.class);

      int responseCode = response.getResponseStatus().getStatusCode();
      String responseContent = response.getEntity();

      System.out.println("responseCode: " + responseCode + ", responseContent：" + responseContent);
    } catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
}
