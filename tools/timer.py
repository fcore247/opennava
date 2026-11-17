"""User-scoped registrations for the hourly OpenNava update checker."""
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys

from navatv import ROOT, Failure, command

LABEL = 'site.navatv.updates'
STORAGE = Path.home() / '.local' / 'share' / 'opennava-updater'


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(0o600)


def change(install, arguments):
    system = sys.platform
    if system not in ('darwin', 'linux'):
        raise Failure('Use Windows Task Scheduler to run tools/update.py on a schedule (see README).')
    if system == 'linux' and shutil.which('systemctl') is None:
        raise Failure('systemd user services are unavailable. Schedule tools/update.py with your usual task scheduler.')
    if install:
        (STORAGE / 'tools').mkdir(parents=True, exist_ok=True)
        for path in ('tools/navatv.py', 'tools/timer.py', 'tools/update.py', 'release-key.sha256'):
            if (ROOT / path).resolve() != (STORAGE / path).resolve():
                shutil.copyfile(ROOT / path, STORAGE / path)
        argv = [sys.executable, str(STORAGE / 'tools' / 'update.py'), '--scheduled', *arguments]
        env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT') if k in os.environ}
    if system == 'darwin':
        agent = Path.home() / 'Library' / 'LaunchAgents' / (LABEL + '.plist')
        domain = f'gui/{os.getuid()}'
        subprocess.run(['launchctl', 'bootout', domain + '/' + LABEL], capture_output=True)
        if install:
            logs = Path.home() / 'Library' / 'Logs' / 'OpenNava'
            logs.mkdir(parents=True, exist_ok=True)
            config = dict(Label=LABEL, ProgramArguments=argv, StartInterval=3600, RunAtLoad=True,
                          EnvironmentVariables=env, StandardOutPath=str(logs / 'update.log'),
                          StandardErrorPath=str(logs / 'update-errors.log'))
            write(agent, plistlib.dumps(config))
            command('launchctl', 'bootstrap', domain, agent)
        else:
            agent.unlink(missing_ok=True)
    else:
        base = Path.home() / '.config' / 'systemd' / 'user'
        service, timer = base / (LABEL + '.service'), base / (LABEL + '.timer')
        if install:
            def quoted(arg):
                if '\n' in arg or '\r' in arg:
                    raise Failure('A systemd argument contains a newline.')
                return '"' + arg.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
            lines = ['[Unit]', 'Description=Update installed OpenNava apps', '[Service]', 'Type=oneshot']
            lines.extend('Environment=' + quoted(k + '=' + v) for k, v in env.items())
            lines.append('ExecStart=:' + ' '.join(map(quoted, argv)))
            write(service, ('\n'.join(lines) + '\n').encode())
            write(timer, b'[Unit]\nDescription=Check OpenNava releases hourly\n[Timer]\nOnCalendar=hourly\nPersistent=true\n[Install]\nWantedBy=timers.target\n')
            command('systemctl', '--user', 'daemon-reload')
            command('systemctl', '--user', 'enable', '--now', timer.name)
        else:
            if timer.exists():
                command('systemctl', '--user', 'disable', '--now', timer.name)
            timer.unlink(missing_ok=True)
            service.unlink(missing_ok=True)
            command('systemctl', '--user', 'daemon-reload')
    print('Hourly update checks enabled.' if install else 'Hourly update checks removed.')
