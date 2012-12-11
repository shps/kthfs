#!/usr/bin/env python

'''
Created on Aug 3, 2012
@author: Hamidreza Afzali <afzali@kth.se>
'''
# First install requests: easy_install requests
# Also install bottle: easy_install bottle

import time, socket
from time import sleep
from datetime import datetime
import multiprocessing, thread
from threading import Lock
from subprocess import Popen, PIPE, STDOUT
import subprocess
import os, sys
import ConfigParser
import requests
import logging
import json
from bottle import Bottle, run, get, post, HTTPResponse

config_mutex = Lock()

service_commands = {}
service_commands["namenode"] =  ["install", "uninstall", "start", "stop"]
service_commands["datanode"] =  ["install", "uninstall", "start", "stop"]

service_commands["mysqlcluster"] =  ["init", "start", "stop"]
service_commands["ndb"] =           ["init","start", "stop"]
service_commands["mysqld"] =        ["start", "stop"]
service_commands["mgmserver"] =     ["start", "stop"]

config_filename = "config.ini"
services_filename = "services"

#logging
logger = logging.getLogger('agent')
logger_formatter = logging.Formatter('%(asctime)s %(levelname)s %(message)s')
logger_file_handler = logging.FileHandler('agent.log') 
logger_stream_handler = logging.StreamHandler()
logger_file_handler.setFormatter(logger_formatter)
logger_stream_handler.setFormatter(logger_formatter)
logger.addHandler(logger_file_handler)
logger.addHandler(logger_stream_handler)
logger.setLevel(logging.INFO)

logger.info("KTHFS-Agent started.")
cores = multiprocessing.cpu_count()

# reading config
try:
    config = ConfigParser.ConfigParser()
    config.read(config_filename)
    heartbeat_interval = config.getfloat('agent','heartbeat-interval')
    watch_interval = config.getfloat('agent','watch-interval')   
    url = config.get('server', 'url')
    restport = config.getint('agent', 'restport')
    if (config.has_option("agent", "name")):
        name = config.get("agent", "name")
    else:
        name = socket.gethostbyaddr(socket.gethostname())[0] 
    rack = config.get("agent", "rack")
    ip = socket.gethostbyname(name)
except Exception, e:
    logger.error("Exception while reading {0} file: {1}".format(config_filename, e))
    sys.exit(1)

# reading services
try:
    services = ConfigParser.ConfigParser()
    services.read(services_filename)
    logger.info("Services: {0}".format(services.sections()))
except Exception, e:
    logger.error("Exception while reading {0} file: {1}".format(services_filename, e))
    sys.exit(1)    
    

class Heartbeat():
    daemon_threads = True 
    def __init__(self):
        init = False
        while True:
            init = Heartbeat.send(init)
            time.sleep(heartbeat_interval) 

    @staticmethod
    def send(init):
            try:     
                disk = os.statvfs("/")
                disk_capacity = disk.f_bsize * disk.f_blocks
                disk_used = disk.f_bsize * (disk.f_blocks - disk.f_bavail)
                mem = MemUsage()
                load1 = os.getloadavg()[0]
                load5 = os.getloadavg()[1]
                load15 = os.getloadavg()[2]

                services_list = Config().readAllForHeartbeat()
                now = long(time.mktime(datetime.now().timetuple()))
                
                headers = {'content-type': 'application/json'}
                payload = {}
                payload["hostname"] = name
                payload["ip"] = ip
                payload["load1"] = load1
                payload["load5"] = load5
                payload["load15"] = load15
                payload["disk-capacity"] = disk_capacity
                payload["disk-used"] = disk_used
                payload['memory-capacity'] = mem.total
                payload['memory-used'] = mem.used
#                Todo: send only needed service info                
                payload["services"] = services_list   
                payload["agent-time"] = str(now)             
                if not init:    
                    payload["cores"] = cores
                    payload["rack"] = rack
                    payload["init"] = "true"
                    logger.info("Sending Init Heartbeat...")
                else:
                    logger.info("Sending Heartbeat...")
                    
                requests.post(url, data=json.dumps(payload), headers=headers)
                init = True
            except Exception as err:
                logger.error("Exception! Retrying... : {0}".format(err))
            return init               

class MemUsage(object):
    def __init__(self):
        self.total = 0
        self.used = 0
        self.free = 0
        self.buffers = 0
        self.cached = 0
        self.init_data()

    def init_data(self):
        command = "free"
        process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE)
        stdout_list = process.communicate()[0].split('\n')
        for line in stdout_list:
            data = line.split()
            try:
                if data[0] == "Mem:":
                    self.total = int(data[1]) * 1024
                    self.used = int(data[2]) * 1024
                    self.free = int(data[3]) * 1024
                    self.buffers = int(data[5]) * 1024
                    self.cached = int(data[6]) * 1024
            except IndexError:
                continue
        
class ExtProcess(): # external process
    @staticmethod
    def isRunning(pid):
        pid = int(pid)
        try:
            os.kill(pid, 0)
        except OSError, e:
            return False
        else:
            return True
        
    @staticmethod        
    def watch(instance, service):
        while True:
            try:
                section = Config().sectionName(instance, service)
                pid_file = Config().get(section, "pid-file")
                pid = Util().readPid(pid_file)
                
                if ExtProcess.isRunning(pid) == True:
                    if not Config().get(section, 'status') == 'Started':
                        logger.info("Process started: {0}/{1} pid={2}".format(instance, service, pid))
                        Service().started(instance, service, pid)            
                else:
                    raise Exception("Process {0} is not running for {1}/{2}".format(pid, instance, service))

            except :# raised if cannot read pid file (IOError), or manually, if pid is not running
                
                logger.info("Proccess.watch: Process is not running: {0}/{1}".format(instance, service))
                if Config().get(section, 'status') == 'Started':
                    logger.info("Process failed: {0}/{1}".format(instance, service))
                    Service().failed(instance, service)

            finally:                    
                sleep(watch_interval)

class Util():
    
    def readPid(self, pid_file):
        with open(pid_file, 'r') as f:
            pid = str(f.readline()).strip()
            return pid
            

class Config(): 

    def sectionName(self, instance, service):
        return "{0}-{1}".format(instance, service)    

    #select items so that the key does not contain 'file' or 'script'
    def readAllForHeartbeat(self):
        services_list = []
        config_mutex.acquire()            
        try:
            for s in services.sections():
                item = {}
                for key, val in services.items(s):
                    #
                    if (not 'file' in key) and (not 'script' in key):
                        item[key] = val
                services_list.append(item)                
        finally:
            config_mutex.release()
            return services_list
        
    def getSection(self, section):
        items = {}
        config_mutex.acquire()
        try:
            for key, val in services.items(section):
                items[key] = val
        finally:
            config_mutex.release()
            return items
    
    def add(self, section, options):
        config_mutex.acquire()
        try:
            services.add_section(section)
            for k, v in options.iteritems():
                services.set(section, k, v)
            services.write(open(services_filename, 'w'))      
        finally:
            config_mutex.release()  
 
    def remove(self, section):
        config_mutex.acquire()
        try:
            services.remove_section(section)
            services.write(open(services_filename, 'w'))
        finally:
            config_mutex.release()   
               
    def edit(self, section, options_to_set, options_to_remove):
        config_mutex.acquire()
        try:
            for k, v in options_to_set.iteritems():
                services.set(section, k, v)
            for k in options_to_remove:
                services.remove_option(section, k)               
            services.write(open(services_filename, 'w'))      
        finally:
            config_mutex.release()

    def get(self, section, option):
        val = ""
        config_mutex.acquire()
        try:         
            val = services.get(section, option)
        finally:
            config_mutex.release()
            return val                

     
class Service:
    
    def add(self, instance, service):
        options = Config().makeConfigOptions(instance, service)
        section = Config().sectionName(instance, service)
        Config().add(section, options)    

    def remove(self, instance, service):
        section = Config().sectionName(instance, service)
        Config().remove(section)

    #need to be completed. Set the status to Initialize?
    def init(self, instance, service):
        section = Config().sectionName(instance, service)
        script = Config().get(section, "init-script")
        try:
            p = Popen(script, shell=True, close_fds=True)
            p.wait()
            returncode = p.returncode
            if not returncode == 0:
                raise Exception("Init script returned a none-zero value")
            return True
        except Exception as err:
            logger.error(err)
            return False

            
    def start(self, instance, service):
        section = Config().sectionName(instance, service)
        script = Config().get(section, "start-script")
        try:
            p = Popen(script, shell=True, close_fds=True)
            p.wait()
            returncode = p.returncode
            if not returncode == 0:
                raise Exception("Start script returned a none-zero value")
            try:
                pid = Util().readPid(Config().get(section, "pid-file"))
            except:
                pid = None
            Service().started(instance, service, pid)
            return pid
        except Exception as err:
            logger.error(err)
            return False
        
    def stop(self, instance, service):
        section = Config().sectionName(instance, service)
        script = Config().get(section, "stop-script")
        try:
            subprocess.check_call(script) # raises exception if not returncode == 0
            now = str(long(time.mktime(datetime.now().timetuple())))
            options_to_set = {'status':'Stopped', 'stop-time':now}
            options_to_remove = ['pid']
            Config().edit(section, options_to_set, options_to_remove)
            return True
        except Exception as err:
            logger.error(err)
            return False

    def failed(self, instance, service):
        section = Config().sectionName(instance, service)
        now = str(long(time.mktime(datetime.now().timetuple())))
#        options_to_set = {'status':'Failed', 'stop-time':now}
        options_to_set = {'status':'Stopped', 'stop-time':now}
        options_to_remove = ['pid']
        Config().edit(section, options_to_set, options_to_remove)

    def started(self, instance, service, pid):
        section = Config().sectionName(instance, service)
        now = str(long(time.mktime(datetime.now().timetuple())))
        options_to_add = {'status':'Started', 'start-time':now}
        if not pid == None:
            options_to_add['pid'] = pid
            
        options_to_remove = ['stop-time']
        Config().edit(section, options_to_add, options_to_remove)


class CommandHandler():

    def respose(self, code, msg):
        resp = HTTPResponse(status=code, output=msg)
        logger.info("{0}: {1}".format(resp, resp.output))
        return resp

    def install(self, instance, service):
        section = Config().sectionName(instance, service)
        if services.has_section(section):
            return CommandHandler().respose(400, 'Already available.')
        else:
            Service().add(instance, service)
            return CommandHandler().respose(200, 'Service Installed.')

    def uninstall(self, instance, service):#    @staticmethod          
        section = Config().sectionName(instance, service)
        if not services.has_section(section):
            return CommandHandler().respose(400, "Not available!")
        else:
            Service().remove(instance, service)
            return CommandHandler().respose(200, "Service uninstalled.")
         
    def init(self, instance, service):
        section = Config().sectionName(instance, service)
        if not services.has_section(section):
            return CommandHandler().respose(400, 'Service not installed.')
        else:
            if Service().init(instance, service) == True:
                return CommandHandler().respose(200, 'Service initialized.')
            else:
                return CommandHandler().respose(400, 'Error: Cannot initialize the service.')
    
    def start(self, instance, service):
        section = Config().sectionName(instance, service)
        if not services.has_section(section):
            return CommandHandler().respose(400, 'Service not installed.')
        elif services.get(section, 'status') == 'Started':
            return CommandHandler().respose(400, 'Service already started.')
        else:
            res = Service().start(instance, service)
            if res == False:
                return CommandHandler().respose(400, 'Error: Cannot start the service.')
            else:
                payload = {}
                payload['pid'] = str(res)
                payload['msg'] = 'Service started.'
                return CommandHandler().respose(200, json.dumps(payload))

    def stop(self, instance, service):
        section = Config().sectionName(instance, service)
        if not services.has_section(section):
            return CommandHandler().respose(400, 'Service not installed.')
        elif not services.get(section, 'status') == 'Started':
            return CommandHandler().respose(400, 'Service is not running.')
        else:
            if Service().stop(instance, service) == True:
                return CommandHandler().respose(200, 'Service stopped.')
            else:
                return CommandHandler().respose(400, 'Error: Cannot stop the service.')

    def readLog(self, instance, service, log_type, lines):
        try:
            lines = int(lines)
            section = Config().sectionName(instance, service)
            log_file_name = Config().get(section, "{0}-file".format(log_type))
            with open(log_file_name) as log_file:
                log = "".join(str(x) for x in (list(log_file)[- lines -1:-1]))
            
            return CommandHandler().respose(200, log)
        
        except Exception as err:
            logger.error(err)
            return CommandHandler().respose(400, "Cannot read file.")

    def readConfig(self, instance, service):
        try:
            section = Config().sectionName(instance, service)
            config_file_name = Config().get(section, "config-file")
            with open(config_file_name) as config_file:
                conf = "".join(str(x) for x in (list(config_file)))
            
            return CommandHandler().respose(200, conf)
        
        except Exception as err:
            logger.error(err)
            return CommandHandler().respose(400, "Cannot read file.")

    def info(self, instance, service):
        
        try:
            section = Config().sectionName(instance, service)
            resp = json.dumps(Config().getSection(section))
            return CommandHandler().respose(200, resp)
        
        except Exception as err:
            logger.error(err)
            return CommandHandler().respose(400, "Cannot read file.")
                
    def refresh(self):
        Heartbeat.send(False);
        return CommandHandler().respose(200, "OK")


if __name__ == '__main__':
    
    thread.start_new_thread(Heartbeat, ())
    
    for s in services.sections():
        instance = Config().get(s, "instance")
        service = Config().get(s, "service")
        service_group = Config().get(s, "service-group")
        if not service == 'mysqlcluster': 
            thread.start_new_thread(ExtProcess.watch,(instance, service,))
        else:
            print "not watching" + service    

#    app = Bottle()
    @get('/ping')
    def ping():
        logger.info('Incoming REST Request:  GET /ping')
        return "Pong! Kthfs-Agent"
        
    @get('/do/<instance>/<service>/<command>')
    def do(instance, service, command):
        logger.info('Incoming REST Request:  GET /do/{0}/{1}/{2}'.format(instance,service,command))
        if not command in service_commands[service]:
            return HTTPResponse(status=400, output='Invalid command.')            
        
        if command == "install":
            return CommandHandler().install(instance, service);
        elif command == "uninstall":
            return CommandHandler().uninstall(instance, service);
        elif command == "start":
            return CommandHandler().start(instance, service);
        elif command == "stop":
            return CommandHandler().stop(instance, service);                
        elif command == "init":
            return CommandHandler().init(instance, service); 
        else:
            return HTTPResponse(status=400, output='Invalid command.')        


    @get('/log/<instance>/<service>/<log_type>/<lines>')
    def log(instance, service, log_type, lines):
        logger.info('Incoming REST Request:  GET /log/{0}/{1}/{2}/{3}'.format(instance,service,log_type,lines))

        if not services.has_section(Config().sectionName(instance, service)):
            return HTTPResponse(status=400, output='Instance/Service not available.')
        elif log_type not in ['stdout', 'stderr']:
            return HTTPResponse(status=400, output='Invalid log type.')                        
            
        return CommandHandler().readLog(instance, service, log_type, lines);

    @get('/config/<instance>/<service>')
    def config(instance, service):
        logger.info('Incoming REST Request:  GET /log/{0}/{1}'.format(instance,service))

        if not services.has_section(Config().sectionName(instance, service)):
            return HTTPResponse(status=400, output='Instance/Service not available.')
            
        return CommandHandler().readConfig(instance, service);

    @get('/info/<instance>/<service>')
    def info(instance, service):
        logger.info('Incoming REST Request:  GET /status/{0}/{1}'.format(instance,service))

        if not services.has_section(Config().sectionName(instance, service)):
            return HTTPResponse(status=400, output='Instance/Service not available.')
            
        return CommandHandler().info(instance, service);

    @get('/refresh') #request heartbeat
    def refresh():
        logger.info('Incoming REST Request:  GET /refresh')
            
        return CommandHandler().refresh();
 

    logger.info("RESTful service started.")
    run(host='0.0.0.0', port=restport)

