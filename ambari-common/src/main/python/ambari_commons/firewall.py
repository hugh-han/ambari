#!/usr/bin/env python

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

import subprocess
import shlex
from ambari_commons import OSCheck, OSConst
from ambari_commons.logging_utils import print_warning_msg
from ambari_commons.os_family_impl import OsFamilyImpl
from ambari_commons.os_utils import run_os_command


class Firewall(object):
  def __init__(self):
    # OS info
    self.OS_VERSION = OSCheck().get_os_major_version()
    self.OS_TYPE = OSCheck.get_os_type()
    self.OS_FAMILY = OSCheck.get_os_family()

  def getFirewallObject(self):
    pass

@OsFamilyImpl(os_family=OSConst.WINSRV_FAMILY)
class FirewallWindows(Firewall):
  def getFirewallObject(self):
    return WindowsFirewallChecks()

@OsFamilyImpl(os_family=OsFamilyImpl.DEFAULT)
class FirewallLinux(Firewall):
  def getFirewallObject(self):
    if self.OS_TYPE == OSConst.OS_UBUNTU:
      return UbuntuFirewallChecks()
    elif self.OS_TYPE == OSConst.OS_FEDORA and int(self.OS_VERSION) >= 18:
      return Fedora18FirewallChecks()
    elif self.OS_FAMILY == OSConst.SUSE_FAMILY:
      return SuseFirewallChecks()
    else:
      return FirewallChecks()

class FirewallChecks(object):
  def __init__(self):
    self.FIREWALL_SERVICE_NAME = "iptables"
    self.SERVICE_SUBCMD = "status"
    # service cmd
    self.SERVICE_CMD = "/sbin/service"
    self.returncode = None
    self.stdoutdata = None
    self.stderrdata = None
    # stdout message
    self.MESSAGE_CHECK_FIREWALL = 'Checking firewall status...'

  def get_firewall_name(self):
    return self.FIREWALL_SERVICE_NAME

  def get_command(self):
    return "%s %s %s" % (self.SERVICE_CMD, self.FIREWALL_SERVICE_NAME, self.SERVICE_SUBCMD)

  def check_result(self):
    result = False
    if self.returncode == 3:
      result = False
    elif self.returncode == 0:
      if "Table: filter" in self.stdoutdata:
        result = True
    return result

  def run_command(self):
    retcode, out, err = run_os_command(self.get_command())
    self.returncode = retcode
    self.stdoutdata = out
    self.stderrdata = err

  def check_firewall(self):
    try:
      self.run_command()
      return self.check_result()
    except OSError:
      return False


class UbuntuFirewallChecks(FirewallChecks):
  def __init__(self):
    super(UbuntuFirewallChecks, self).__init__()
    self.FIREWALL_SERVICE_NAME = "ufw"

  def get_command(self):
    return "%s %s" % (self.FIREWALL_SERVICE_NAME, self.SERVICE_SUBCMD)

  def check_result(self):
    # On ubuntu, the status command returns 0 whether running or not
    result = False
    if self.returncode == 0:
      if "Status: inactive" in self.stdoutdata:
        result = False
      elif "Status: active" in self.stdoutdata:
        result = True
    return result

class Fedora18FirewallChecks(FirewallChecks):
  def __init__(self):
    super(Fedora18FirewallChecks, self).__init__()

  def get_command(self):
    return "systemctl is-active %s" % (self.FIREWALL_SERVICE_NAME)

  def check_result(self):
    result = False
    if self.returncode == 0:
      if "active" in self.stdoutdata:
        result = True
    return result

class SuseFirewallChecks(FirewallChecks):
  def __init__(self):
    super(SuseFirewallChecks, self).__init__()
    self.FIREWALL_SERVICE_NAME = "SuSEfirewall2"

  def get_command(self):
    return "%s %s" % (self.FIREWALL_SERVICE_NAME, self.SERVICE_SUBCMD)

  def check_result(self):
    result = False
    if self.returncode == 0:
      if "SuSEfirewall2 not active" in self.stdoutdata:
        result = False
      elif "### iptables" in self.stdoutdata:
        result = True
    return result


class WindowsFirewallChecks(FirewallChecks):
  def __init__(self):
    super(WindowsFirewallChecks, self).__init__()
    self.FIREWALL_SERVICE_NAME = "MpsSvc"

  def run_command(self):
    from ambari_commons.os_windows import run_powershell_script, CHECK_FIREWALL_SCRIPT, WinServiceController, SERVICE_STATUS_RUNNING

    if WinServiceController.QueryStatus(self.FIREWALL_SERVICE_NAME) != SERVICE_STATUS_RUNNING:
      self.returncode = 0
      self.stdoutdata = ""
      self.stderrdata = ""
    else:
      retcode, out, err = run_powershell_script(CHECK_FIREWALL_SCRIPT)
      self.returncode = retcode
      self.stdoutdata = out
      self.stderrdata = err

  def check_result(self):
    if self.returncode != 0:
      print_warning_msg("Unable to check firewall status:{0}".format(self.stderrdata))
      return False
    profiles_status = [i for i in self.stdoutdata.split("\n") if not i == ""]
    if "1" in profiles_status:
      enabled_profiles = []
      if profiles_status[0] == "1":
        enabled_profiles.append("DomainProfile")
      if profiles_status[1] == "1":
        enabled_profiles.append("StandardProfile")
      if profiles_status[2] == "1":
        enabled_profiles.append("PublicProfile")
      print_warning_msg(
        "Following firewall profiles are enabled:{0}. Make sure that the firewall is properly configured.".format(
          ",".join(enabled_profiles)))
      return True
    return False
