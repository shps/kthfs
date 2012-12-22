# one of: debug, verbose, notice, warning

version="7"
majorVersion="2"
minorVersion="9"
versionStr= "7.2.9"

default[:ndb][:loglevel]  = "notice"
default[:ndb][:mgm_server] = "10.0.2.15"
default[:ndb][:user]      = "root"
default[:ndb][:group]      = "root"
default[:ndb][:port]      = "1186"
default[:ndb][:package_src] = "from/http://cdn.mysql.com/"
default[:ndb][:package_url] = "http://dev.mysql.com/get/Downloads/MySQL-Cluster-#{version}.#{majorVersion}/mysql-cluster-gpl-#{versionStr}-linux2.6-x86_64.tar.gz"
default[:ndb][:connect_string] = "#{default[:ndb][:mgm_server]}:#{ default[:ndb][:port]}"

default[:ndb][:data_memory] = 50
default[:ndb][:index_memory] = 10
default[:ndb][:data_nodes] = %w{ 10.0.2.15 } # cloud8.sics.se cloud9.sics.se cloud16.sics.se 
default[:ndb][:num_replicas] = 1

default[:mgm][:scripts] = %w{ enter-singleuser-mode.sh mgm-client.sh mgm-server-start.sh mgm-server-stop.sh mgm-server-restart.sh cluster-shutdown.sh  exit-singleuser-mode.sh }
default[:ndb][:scripts] = %w{ backup-start.sh backup-restore.sh ndbd-start.sh ndbd-init.sh ndbd-stop.sh ndbd-restart.sh }
default[:mysql][:scripts] = %w{ get-mysql-socket.sh get-mysql-port.sh mysql-server-start.sh mysql-server-stop.sh mysql-server-restart.sh mysql-client.sh }

default[:ndb][:version] = #{versionStr}
default[:ndb][:base_dir] = "/var/lib/mysql-cluster/ndb-#{versionStr}"
default[:ndb][:log_dir] = "#{default[:ndb][:base_dir]}" + "/log"
default[:ndb][:data_dir] = "/var/lib/mysql-cluster/ndb_data"
default[:ndb][:scripts_dir] = "#{default[:ndb][:base_dir]}" + "/scripts"
default[:ndb][:mgm_dir] = "#{default[:ndb][:base_dir]}" + "/mgmd"

default[:ndb][:mysql_server_dir] = "#{default[:ndb][:base_dir]}" + "/mysql"
default[:ndb][:mysql_port] = "3306"
default[:ndb][:mysql_socket] = "/tmp/mysql.sock"
default[:ndb][:kthfs_services] = "/var/lib/kthfsagent/services"
default[:ndb][:inifile_gem] = "http://lucan.sics.se/kthfs/inifile-2.0.2.gem"
default[:ndb][:instance] = "hdfs1"

default[:mysql][:base_dir] = "/usr/local/mysql-#{versionStr}"
default[:mysql][:user]      = "kthfs"
default[:mysql][:password]  = "kthfs"
default[:collectd][:conf] = "/etc/collectd/collectd.conf"
