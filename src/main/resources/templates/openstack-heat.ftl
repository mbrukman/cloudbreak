heat_template_version: 2014-10-16

description: >
  Heat OpenStack-native for Ambari

parameters:

  key_name:
    type: string
    description : Name of a KeyPair to enable SSH access to the instance
  tenant_id:
    type: string
    description : ID of the tenant
  image_id:
    type: string
    description: ID of the image
    default: Ubuntu 14.04 LTS amd64
  app_net_cidr:
    type: string
    description: app network address (CIDR notation)
    default: 10.10.1.0/24
  app_net_gateway:
    type: string
    description: app network gateway address
    default: 10.10.1.1
  app_net_pool_start:
    type: string
    description: Start of app network IP address allocation pool
    default: 10.10.1.4
  app_net_pool_end:
    type: string
    description: End of app network IP address allocation pool
    default: 10.10.1.254
  public_net_id:
    type: string
    description: The ID of the public network. You will need to replace it with your DevStack public network ID
  app_network_id:
    type: string
    description: Fixed network id
    default: ad2d511e-09be-42f0-8295-c0e296896f2b
  app_subnet_id:
    type: string
    description: Fixed subnet id
    default: d0ef818e-15a5-45fa-9297-41ba189ab1f2

resources:


  <#list agents as agent>
        
  ambari_instance_${agent_index}:
    type: OS::Nova::Server
    properties:
      image: { get_param: image_id }
      flavor: ${agent.flavor}
      key_name: { get_param: key_name }
      metadata: ${agent.metadata}
      networks:
        - port: { get_resource: ambari_app_port_${agent_index} }
      user_data:
        str_replace:
          template: |
${userdata}
          params:
            public_net_id: { get_param: public_net_id }
   

  ambari_app_port_${agent_index}:
      type: OS::Neutron::Port
      properties:
        network_id: { get_param: app_network_id }
        fixed_ips:
          - subnet_id: { get_param: app_subnet_id }


  </#list>     

        
outputs:
  <#list agents as agent>
  instance_uuid_${agent_index}:
    value: { get_attr: [ambari_instance_${agent_index}, show, id] }
  </#list>
