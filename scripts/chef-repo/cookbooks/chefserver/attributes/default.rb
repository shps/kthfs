# Default values for configuration parameters
default[:chef][:user] = "chef"
default[:chef][:group] = "chef"
default[:chef][:password] = "kthfs12"
default[:amqp][:password] = "kthfs12"

default[:web][:user] = "kthfs"
default[:web][:password] = "kthfs12"

default[:chef][:base_dir] = "/var/lib/chef"
default[:chef][:port] = 4000
default[:chef][:client] = "webui"
default[:chef][:org] = "kth"
default[:chef][:server_url] = "http://localhost:4000"
#"http://<%= node['ipaddress'] %>:4000"

default[:ruby][:base_dir] = "/opt/vagrant_ruby/"

# Amazon Web Services
default[:aws][:user] = "jdowling"
default[:aws_access_key_id] = "AKIAJRR5Z45K4ZSIFYWQ"
default[:aws_secret_access_key] = "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"

default[:aws][:zone] = "us-east-1" #"eu-west-1" 
default[:aws][:instance_type] = "ebs" # or instance
default[:aws][:image_id] = "ami-1aad5273" # "ami-ffcdce8b"
default[:aws][:image_name] = "ironfan-natty" 
# ubuntu12.04-ironfan doesn't work.
default[:aws][:bootstrap_distro] = "ubuntu11.04-ironfan"



# Openstack
default[:openstack_username] = ""
default[:openstack_password] = ""
# "http://cloud.mycompany.com:5000/v2.0/tokens"
default[:openstack_auth_url] = ""
default[:openstack_tenant] = ""


# Rackspace:
default[:rackspace_api_key]      = "Your Rackspace API Key"
default[:rackspace_api_username] = "Your Rackspace API username"
 
# Terremark
default[:terremark_password] = "Your Terremark Password"
default[:terremark_username] = "Your Terremark Username"
default[:terremark_service]  = "Your Terremark Service name"
 
# Eucalyptus
default[:euca_access_key_id]     = "Your Eucalyptus Access Key"
default[:euca_secret_access_key] = "Your Eucalyptus Secret Access Key"
default[:euca_api_endpoint]      = "http://ecc.eucalyptus.com:8773/services/Eucalyptus"


default[:ironfan][:gems] = %w{ rake-10.0.3 i18n-0.6.1 multi_json-1.5.0 activesupport-3.2.11 builder-3.1.4 activemodel-3.2.11 addressable-2.3.2 archive-tar-minitar-0.5.2 bunny-0.8.0 erubis-2.7.0 highline-1.6.15 json-1.7.5 mixlib-log-1.4.1 mixlib-authentication-1.3.0 mixlib-cli-1.2.2 mixlib-config-1.1.2 mixlib-shellout-1.1.0 moneta-0.7.1 net-ssh-2.6.2 net-ssh-gateway-1.1.0 net-ssh-multi-1.1 ipaddress-0.8.0 systemu-2.5.2 yajl-ruby-1.1.0 ohai-6.14.0 mime-types-1.19 rest-client-1.6.7 polyglot-0.3.3 treetop-1.4.12 uuidtools-2.1.3 chef-10.16.6 hashie-1.2.0 minitar-0.5.4 facter-1.6.17 timers-1.0.2 celluloid-0.12.4 multipart-post-1.1.5 faraday-0.8.4 net-http-persistent-2.8 ridley-0.6.2 solve-0.4.1 thor-0.16.0 ffi-1.2.0 childprocess-0.3.6 log4r-1.1.10 net-scp-1.0.4 vagrant-1.0.5 berkshelf-1.1.1 bundler-1.2.3 coderay-1.0.8 configliere-0.4.18 diff-lcs-1.1.3 gherkin-2.11.5 cucumber-1.2.1 excon-0.16.10 formatador-0.2.4 nokogiri-1.5.6 ruby-hmac-0.4.0 fog-1.8.0 git-1.2.5 gorillib-0.5.0 posix-spawn-0.3.6 grit-2.5.0 listen-0.7.0 lumberjack-1.0.2 method_source-0.8.1 slop-3.3.3 pry-0.9.10 guard-1.6.1 guard-chef-0.0.2 guard-cucumber-1.3.0 spoon-0.0.1 guard-process-1.0.5 ironfan-4.7.4 rdoc-3.12 jeweler-1.8.4 redcarpet-2.2.2 rspec-core-2.12.2 rspec-expectations-2.12.1 rspec-mocks-2.12.1 rspec-2.12.0 ruby_gntp-0.3.4 yard-0.8.3 chozo-0.4.2 gorillib-0.5.0}

default[:chef][:gems] = %w{ ruby-shadow-2.1.4 addressable-2.3.2 amqp-0.9.8 builder-3.1.4 bundler-1.2.3 bunny-0.8.0 chef-10.18.2 chef-expander-10.18.2 chef-server-api-10.18.2 chef-server-webui-10.18.2 chef-solr-10.18.2 coderay-1.0.8 daemons-1.1.9 dep_selector-0.0.8 em-http-request-0.2.15 erubis-2.7.0 eventmachine-1.0.0 excon-0.16.10 extlib-0.9.15 fast_xs-0.8.0 haml-3.1.7 highline-1.6.15 ipaddress-0.8.0 json-1.7.5 merb-assets-1.1.3 merb-core-1.1.3 merb-haml-1.1.3 merb-helpers-1.1.3 merb-param-protection-1.1.3 mime-types-1.19 mixlib-authentication-1.3.0 mixlib-cli-1.3.0 mixlib-config-1.1.2 mixlib-log-1.4.1 mixlib-shellout-1.1.0 moneta-0.7.1 net-ssh-2.6.2 net-ssh-gateway-1.1.0 net-ssh-multi-1.1 ohai-6.16.0 polyglot-0.3.3 rack-1.5.0 rake-10.0.3 rest-client-1.6.7 ruby-openid-2.2.2 systemu-2.5.2 thin-1.5.0 treetop-1.4.12 uuidtools-2.1.3 yajl-ruby-1.1.0 }

#gem install em-http-request -v 0.2.15
#em-http-request (0.2.15)
#em-http-request (1.0.3)
#eventmachine (1.0.0, 0.12.10)
#extlib (0.9.16)
#fast_xs (0.7.3)
#json (1.6.1)
#moneta (0.6.0)
#net-ssh (2.2.2)
