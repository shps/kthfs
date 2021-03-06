include_recipe "python"

user node[:kthfs][:user] do
  action :create
  system true
  shell "/bin/bash"
end

inifile_gem = "inifile-2.0.2.gem"
cookbook_file "#{Chef::Config[:file_cache_path]}/#{inifile_gem}" do
  source "#{inifile_gem}"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0755
  action :create_if_missing
end

gem_package "inifile" do
  source "#{Chef::Config[:file_cache_path]}/#{inifile_gem}"
  action :install
end

requests="requests-1.0.3"
cookbook_file "#{Chef::Config[:file_cache_path]}/#{requests}.tar.gz" do
  source "#{requests}.tar.gz"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0755
  action :create_if_missing
end

bottle="bottle-0.11.4"
cookbook_file "#{Chef::Config[:file_cache_path]}/#{bottle}.tar.gz" do
  source "#{bottle}.tar.gz"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0755
  action :create_if_missing
end


# easy_install_package "requests" do
#   options "-U"
#   action :install
# end

# easy_install_package "bottle" do
#   options "-U"
#   action :install
# end

cherry="CherryPy-3.2.2"
cookbook_file "#{Chef::Config[:file_cache_path]}/#{cherry}.tar.gz" do
  source "#{cherry}.tar.gz"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0755
end

openSsl="pyOpenSSL-0.13"
cookbook_file "#{Chef::Config[:file_cache_path]}/#{openSsl}.tar.gz" do
  source "#{openSsl}.tar.gz"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0755
  action :create_if_missing
end

 bash "install_python" do
    code <<-EOF
  tar zxf "#{Chef::Config[:file_cache_path]}/#{bottle}.tar.gz"
  cd #{bottle}
  python setup.py install
  cd ..
  tar zxf "#{Chef::Config[:file_cache_path]}/#{requests}.tar.gz"
  cd #{requests}
  python setup.py install
  cd ..
  tar zxf "#{Chef::Config[:file_cache_path]}/#{cherry}.tar.gz"
  cd #{cherry}
  python setup.py install
  cd ..
  tar zxf "#{Chef::Config[:file_cache_path]}/#{openSsl}.tar.gz"
  cd #{openSsl}
  python setup.py install
 EOF
 end

directory node[:kthfs][:base_dir] do
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode "755"
  action :create
  recursive true
end

service "kthfsagent" do
  supports :restart => true, :start => true, :stop => true
end

template "/etc/init.d/kthfsagent" do
  source "kthfsagent.erb"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0655
  notifies :enable, resources(:service => "kthfsagent")
end

template"#{node[:kthfs][:base_dir]}/agent.py" do
  source "agent.py.erb"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0655
  notifies :enable, resources(:service => "kthfsagent")
end

# cookbook_file "#{node[:kthfs][:base_dir]}/agent.py" do
#   source "agent.py"
#   owner node[:kthfs][:user]
#   group node[:kthfs][:user]
#   mode 0755
# #  notifies :restart, resources(:service => "kthfsagent")
# end

['start-agent.sh', 'stop-agent.sh', 'restart-agent.sh', 'services', 'get-pid.sh'].each do |script|
  Chef::Log.info "Installing #{script}"
  template "#{node[:kthfs][:base_dir]}/#{script}" do
    source "#{script}.erb"
    owner node[:kthfs][:user]
    group node[:kthfs][:user]
    mode 0655
#    notifies :restart, resources(:service => "kthfsagent")
  end
end 

template "#{node[:kthfs][:base_dir]}/config.ini" do
  source "config.ini.erb"
  owner node[:kthfs][:user]
  group node[:kthfs][:user]
  mode 0644
  variables({
              :name => node['ipaddress'],
              :rack => '/default'
            })
#  notifies :start, resources(:service => "kthfsagent"), :immediately
end

#TODO install MONIT to restart the agent if it crashes

 bash "start_kthfsagent" do
    code <<-EOF
  nohup #{node[:kthfs][:base_dir]}/agent.py &> /dev/null &

# echo ""
# echo "Checking if the agent is running...."
# echo ""
# PID_FILE=#{node[:kthfs][:pid_file]}
# PID=""
# if [ -e $PID_FILE ] ; then
#   PID=`cat $PID_FILE`
# fi
# echo "PID is $PID"
# echo ""
# kill -0 $PID 2> /dev/null

# if [ $? -ne 0 ]; then
# 	echo "Agent is not running."
#   nohup #{node[:kthfs][:base_dir]}/agent.py &> /dev/null &
# else
#     echo "Agent is already running with pid=$PID."
#     exit 1
# fi
 EOF
 end
