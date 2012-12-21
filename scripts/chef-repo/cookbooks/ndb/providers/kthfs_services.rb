action :install_ndbd do
  ini_file = IniFile.load(node[:ndb][:kthfs_services], :comment => ';#')
  Chef::Log.info "Loaded services for agent into ini-file."

  if ini_file.has_section?("hdfs1-ndb")
    Chef::Log.info "Over-writing an existing section in the ini file."
    ini_file.delete_section("hdfs1-ndb")
  end
  ini_file["hdfs1-ndb"] = {
    'status' => 'Stopped',
    'instance' => "#{node[:ndb][:instance]}",
    'service-group'  => 'mysqlcluster',
    'service'  => 'ndb',
    'init-script'  => "#{node[:ndb][:scripts_dir]}/ndbd-init.sh",
    'stop-script'  => "#{node[:ndb][:scripts_dir]}/ndbd-stop.sh",
    'start-script'  => "#{node[:ndb][:scripts_dir]}/ndbd-start.sh",
    'pid-file'  => "#{node[:ndb][:log_dir]}/ndb_#{new_resource.node_id}.pid",
    'stdout-file'  => "#{node[:ndb][:log_dir]}/ndb_#{new_resource.node_id}_out.log",
    'stderr-file'  => "#{node[:ndb][:log_dir]}/ndb_#{new_resource.node_id}_err.log"
  } 
  ini_file.save
  Chef::Log.info "Saved an updated copy of services file at the kthfsagent."
end

action :install_mgmd do
  ini_file = IniFile.load(node[:ndb][:kthfs_services], :comment => ';#')

  if ini_file.has_section?('hdfs1-mysqlcluster') then
    Chef::Log.info "Over-writing an existing mysqlcluster section in the ini file."
    ini_file.delete_section("hdfs1-mysqlcluster")
  end

  if ini_file.has_section?('hdfs1-mgmserver') then
    Chef::Log.info "Over-writing an existing mgmserver section in the ini file."
    ini_file.delete_section("hdfs1-mgmserver")
  end

  ini_file['hdfs1-mysqlcluster'] = {
    'status' => 'Stopped',
    'instance' => "#{node[:ndb][:instance]}",
    'service-group'  => 'mysqlcluster',
    'service'  => 'mysqlcluster',
    'stop-script'  => "#{node[:ndb][:scripts_dir]}/cluster-shutdown.sh",
    'start-script'  => "",
    'pid-file'  => "",
    'stdout-file'  => "#{node[:ndb][:log_dir]}/cluster.log",
    'stderr-file'  => ""
  } 

  ini_file['hdfs1-mgmserver'] = {
    'status' => '',
    'instance' => "#{node[:ndb][:instance]}",
    'service-group'  => 'mysqlcluster',
    'service'  => 'mgmserver',
    'stop-script'  => "#{node[:ndb][:scripts_dir]}/mgm-server-stop.sh",
    'start-script'  => "#{node[:ndb][:scripts_dir]}/mgm-server-start.sh",
    'pid-file'  => "#{node[:ndb][:log_dir]}/ndb_63.pid",
    'stdout-file'  => "#{node[:ndb][:log_dir]}/ndb_63.out.log",
    'stderr-file'  => "#{node[:ndb][:log_dir]}/ndb_63.err.log"
  } 

  ini_file.save
  Chef::Log.info "Saved an updated copy of services file at the kthfsagent." # 

end

action :install_mysqld do

  Chef::Log.info "Loading ini-file: #{node[:ndb][:kthfs_services]}"
  ini_file = IniFile.load(node[:ndb][:kthfs_services], :comment => ';#')
  # if ini_file.has_section?('hdfs1-mysqld')
  #   Chef::Log.info "Over-writing an existing section in the ini file."
  #   ini_file.delete_section("hdfs1-mysqld")
  # end

  ini_file["hdfs1-mysqld"] = {
    'status' => 'Stopped',
    'instance' => "#{node[:ndb][:instance]}",
    'service-group'  => 'mysqlcluster',
    'service'  => 'mysqld',
    'stop-script'  => "#{node[:ndb][:scripts_dir]}/mysql-server-stop.sh",
    'start-script'  => "#{node[:ndb][:scripts_dir]}/mysql-server-start.sh",
    'pid-file'  => "#{node[:ndb][:log_dir]}/mysql.pid",
    'stdout-file'  => "#{node[:ndb][:log_dir]}/mysql.out.log",
    'stderr-file'  => "#{node[:ndb][:log_dir]}/mysql.err.log"
  } 
  ini_file.save
  Chef::Log.info "Saved an updated copy of services file at the kthfsagent."


  bash 'mysq_install_db' do
    code <<-EOF

    cd #{node[:mysql][:base_dir]}
    # --force causes mysql_install_db to run even if DNS does not work. In that case, grant table entries that normally use host names will use IP addresses.
    #{node[:mysql][:base_dir]}/scripts/mysql_install_db --basedir=#{node[:mysql][:base_dir]} --defaults-file=#{node[:ndb][:base_dir]}/my.cnf --force 

    EOF
      not_if { ::File.exists?( "#{node[:ndb][:mysql_server_dir]}/mysql" ) }
  end


  # mysql options -uroot mysql < /usr/local/mysql/share/ndb_dist_priv.sql
  # SELECT ROUTINE_NAME, ROUTINE_SCHEMA, ROUTINE_TYPE 
  # ->     FROM INFORMATION_SCHEMA.ROUTINES 
  # ->     WHERE ROUTINE_NAME LIKE 'mysql_cluster%'
  # ->     ORDER BY ROUTINE_TYPE;
  # load the users using distributed privileges
  # http://dev.mysql.com/doc/refman/5.5/en/mysql-cluster-privilege-distribution.html
  distusers="ndb_dist_priv.sql"
  cached_distusers = "#{Chef::Config[:file_cache_path]}/#{distusers}"
  Chef::Log.info "Installing #{distusers} to #{cached_distusers}"

  cookbook_file "#{cached_distusers}" do
    source "#{distusers}"
    owner "root"
    group "root"
    mode "0755"
    action :create_if_missing
  end

  bash 'create_distributed_privileges' do
   code <<-EOF
     #{node[:ndb][:scripts_dir]}/mysql-client.sh < #{cached_distusers}
     #{node[:ndb][:scripts_dir]}/mysql-client.sh -e "CALL mysql.mysql_cluster_move_privileges" mysql
     echo "Verifying successful conversion of tables.."
     #{node[:ndb][:scripts_dir]}/mysql-client.sh -e "SELECT CONCAT('Conversion ', IF(mysql.mysql_cluster_privileges_are_distributed(), 'succeeded', 'failed'), '.') AS Result;" mysql | grep "Conversion succeeded" 
    EOF
    not_if { `#{node[:ndb][:scripts_dir]}/mysql-client.sh -e "SELECT CONCAT('Conversion ', IF(mysql.mysql_cluster_privileges_are_distributed(), 'succeeded', 'failed'), '.') AS Result;" mysql | grep "Conversion succeeded"` }
  end

end