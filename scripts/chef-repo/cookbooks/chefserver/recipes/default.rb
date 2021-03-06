# Actually used this tutorial
# http://jtimberman.housepub.org/blog/2012/11/17/install-chef-10-server-on-ubuntu-with-ruby-1-dot-9/

# Knife/encrypting passwords/creating users tutorial
# http://www.jasongrimes.org/2012/06/provisioning-a-lamp-stack-with-chef-vagrant-and-ec2-2-of-3/

# Other tutorials
# How to install chef server...
# https://github.com/pikesley/catering-college
# More detailed version of above script here:
# https://github.com/kaldrenon/install-chef-server
# How to install chef-server using chef-solo:
# http://wiki.opscode.com/display/chef/Installing+Chef+Server+using+Chef+Solo


ChefVersion="10.21.0"
HomeDir="#{node[:chef][:base_dir]}"
user node[:chef][:user] do
  action :create
  shell "/bin/bash"
  supports :manage_home=>true
  home "#{HomeDir}"
end

bash "add_user_sudoers" do
  user "root"
  code <<-EOF
  echo "#{node[:chef][:user]} ALL = (root) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/#{node[:chef][:user]}
  sudo chmod 0440 /etc/sudoers.d/#{node[:chef][:user]}
  apt-get update -y
  EOF
end

for install_package in %w{ruby1.9.1-dev build-essential wget ssl-cert curl make expect libgecode-dev rubygems1.9.1 }
  package "#{install_package}" do
    action :install
    options "--force-yes"
  end
end

directory "/etc/chef/certificates" do
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  action :create
  recursive true
  mode 0755
end

template "/etc/chef/solo.rb" do
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  source "solo.rb.erb"
  mode 0755
end
 template "/etc/chef/solr.rb" do
   source "solr.rb.erb"
   owner node[:chef][:user]
   group node[:chef][:user]
   mode 0755
 end
template "/etc/chef/chef.json" do
  source "chef.json.erb"
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  mode 0755
end

template "/etc/chef/server.rb" do
  source "server.rb.erb"
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  mode 0755
end

template "/etc/chef/webui.rb" do
  source "webui.rb.erb"
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  mode 0755
end

# We can reduce installation time by caching all the gems in the recipe, and then
# installing them 'locally'. 
#
# for install_gem in node[:chef][:gems]
#   cookbook_file "#{Chef::Config[:file_cache_path]}/#{install_gem}.gem" do
#     source "#{install_gem}.gem"
#     owner node[:chef][:user]
#     group node[:chef][:user]
#     mode 0755
#     action :create_if_missing
#   end
#   gem_package "#{install_gem}" do
#     source "#{Chef::Config[:file_cache_path]}/#{install_gem}.gem"
#     action :install
# # Passing options as a string spawns a new process. The options hash is more efficient.
# #    options "--no-rdoc --no-ri --ignore-dependencies"
#     options(:ignore_dependencies => true, :no_rdoc => true, :no_ri => true)
#   end
# end



bash "install_chef_server" do
  user "#{node[:chef][:user]}"
  code <<-EOF
   
   REALLY_GEM_UPDATE_SYSTEM=yes sudo -E gem update --system
   sudo gem install chef --no-ri --no-rdoc -v #{ChefVersion}
   sudo chef-solo -o chef-server::rubygems-install
   sudo gem install chef-server-webui --no-ri --no-rdoc -v #{ChefVersion}
   sudo gem install chef-server-api --no-ri --no-rdoc -v #{ChefVersion}
   sudo gem install chef-solr --no-ri --no-rdoc -v #{ChefVersion}
# chef-expander broken on Ubuntu 12.10+
#  https://tickets.opscode.com/browse/CHEF-3567, https://tickets.opscode.com/browse/CHEF-3495
   sudo gem install chef-expander --no-ri --no-rdoc

   sudo chown -R #{node[:chef][:user]} /var/log/chef 
   sudo chown -R #{node[:chef][:user]} /etc/chef/
   sudo chown -R #{node[:chef][:user]} /var/cache/chef
   sudo chown -R #{node[:chef][:user]} #{HomeDir}

# Installing the chef gem also causes a chef user to be created.
# However, with the wrong shell (csh). Change the shell back to bash.
sudo usermod -s /bin/bash #{node[:chef][:user]}
#sudo usermod -d #{HomeDir} #{node[:chef][:user]}

  EOF
not_if "which chef-server-expander"
end

for install_service in %w{ chef-server chef-solr chef-server-webui chef-expander }
  service "#{install_service}" do
    provider Chef::Provider::Service::Upstart
    supports :restart => true, :stop => true, :start => true
    action :nothing
  end
  template "/etc/init/#{install_service}.conf" do
    source "#{install_service}.conf.erb"
    owner "#{node[:chef][:user]}"
    group "#{node[:chef][:user]}"
    mode 0755
    notifies :enable, "service[#{install_service}]"
    notifies :start, "service[#{install_service}]", :immediately
  end
end
 
template "#{Chef::Config[:file_cache_path]}/knife-config.sh" do
  source "knife-config.sh.erb"
  owner "#{node[:chef][:user]}"
  group "#{node[:chef][:user]}"
  mode 0755
end

bash "configure_knife" do
user "#{node[:chef][:user]}"
code <<-EOF

# Wait for chef servers to start
wait_chef=30
timeout=0
while [ $timeout -lt $wait_chef ] ; do
    sleep 1
    ps -ef | grep chef-server > /dev/null
    $? -eq 0 && break
    echo -n "."
    timeout=`expr $timeout + 1`
done
echo "Chef server started in $timeout seconds"

test -d #{HomeDir}/.chef && rm -rf #{HomeDir}/.chef
cd #{HomeDir}
sudo cp /etc/chef/*.pem #{HomeDir}/
sudo chown #{node[:chef][:user]} #{HomeDir}/*.pem

mkdir #{HomeDir}/.chef
#{Chef::Config[:file_cache_path]}/knife-config.sh
cp #{HomeDir}/.chef/#{node[:chef][:user]}.pem #{HomeDir}/#{node[:chef][:user]}.pem

EOF
not_if "test -f #{HomeDir}/#{node[:chef][:user]}.pem || test -f #{HomeDir}/.chef/credentials/#{node[:chef][:user]}.pem"
end
