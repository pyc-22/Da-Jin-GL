"""Assemble rc.3 from verified builds; exclude runtime data and signing material."""
from pathlib import Path
import hashlib
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RELEASES = ROOT.parent / 'releases'
VERSION = '1.0.0-rc.3'
OLD = RELEASES / '打金店管理系统-V1.0.0-rc.1'
PATCH = RELEASES / f'dajin-mobile-cloud-fix-{VERSION}'
FULL = RELEASES / f'dajin-system-{VERSION}'


def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def manifest(folder):
    paths = sorted(p for p in folder.rglob('*') if p.is_file() and p.name != 'SHA256SUMS.txt')
    for path in paths:
        assert path.name not in ('.env', 'signing.json', 'local.properties'), path
        assert path.suffix.lower() not in ('.jks', '.keystore', '.p12', '.key'), path
    (folder / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.relative_to(folder).as_posix()}\n' for p in paths), encoding='utf-8', newline='\n')


def archive(folder):
    manifest(folder)
    # Version dots are part of the directory name.
    target = RELEASES / (folder.name + '.zip')
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for path in sorted(folder.rglob('*')):
            if path.is_file(): bundle.write(path, path.relative_to(folder.parent).as_posix())
    with zipfile.ZipFile(target) as bundle:
        assert bundle.testzip() is None
    Path(str(target) + '.sha256').write_text(f'{digest(target)}  {target.name}\n', encoding='utf-8', newline='\n')
    print(f'{target}: {target.stat().st_size:,} bytes', flush=True)


def main():
    if PATCH.exists() or FULL.exists():
        raise SystemExit('Destination already exists; preserve released artifacts and choose a new version.')
    assert (ROOT / 'runtime-logs/rc3-schema.sql').is_file(), 'Run New-ProductionSchema.ps1 first'
    for name in ('apply-mobile-cloud-fix.sh', 'rollback-mobile-cloud-fix.sh', 'patch-nginx-mobile.py'):
        copy(ROOT / 'scripts' / name, PATCH / name)
    payload = PATCH / 'payload'
    copy(ROOT / 'backend/target/backend-1.0.0-rc.1.jar', payload / 'backend.jar')
    copy(ROOT / 'mobile/nginx.conf', payload / 'mobile.nginx.conf')
    copy(ROOT / 'mobile/Dockerfile.release', payload / 'mobile.Dockerfile')
    copy(ROOT / 'runtime-logs/rc3-schema.sql', payload / 'schema.sql')
    copy(ROOT / 'db/migrations/20260920_handover.sql', payload / '20260920_handover.sql')
    shutil.copytree(ROOT / 'mobile/dist/build/h5', payload / 'h5')
    guide = ROOT / 'docs/移动端云部署修复交付-20260920.md'
    copy(guide, PATCH / 'README.md')

    # Explicit subdirectories/files; never copy live data, backups or credentials.
    for directory in ('cashier', 'docs'):
        shutil.copytree(OLD / directory, FULL / directory)
    shutil.copytree(OLD / 'server/admin-web/dist', FULL / 'server/admin-web/dist')
    for name in ('docker-compose.yml', '.env.production.example', 'backup.sh', 'restore.sh', 'backup.ps1', 'restore.ps1', 'register-backup-task.ps1'):
        copy(OLD / 'server' / name, FULL / 'server' / name)
    for name in ('docker-compose.yml', '.env.production.example'):
        p = FULL / 'server' / name
        p.write_text(p.read_text(encoding='utf-8-sig').replace('1.0.0-rc.1', VERSION), encoding='utf-8', newline='\n')
    for name in ('admin-web', 'mobile', 'backend'):
        copy(ROOT / name / 'Dockerfile.release', FULL / 'server' / name / 'Dockerfile')
    for name in ('admin-web', 'mobile'):
        copy(ROOT / name / 'nginx.conf', FULL / 'server' / name / 'nginx.conf')
    copy(payload / 'backend.jar', FULL / 'server/backend/backend-1.0.0-rc.1.jar')
    copy(payload / 'schema.sql', FULL / 'server/db/schema.sql')
    copy(payload / '20260920_handover.sql', FULL / 'server/db/migrations/20260920_handover.sql')
    shutil.copytree(payload / 'h5', FULL / 'server/mobile/dist')
    for name in ('data', 'backup'):
        p = FULL / 'server' / name
        p.mkdir()
        (p / '.gitkeep').touch()
    for suffix in ('', '.sha256', '.verification.txt'):
        apk = f'dajin-mobile-{VERSION}.apk{suffix}'
        copy(RELEASES / 'android' / apk, FULL / 'mobile' / apk)
    copy(guide, FULL / '发布说明.md')
    copy(guide, FULL / 'docs/发布说明.md')
    copy(guide, FULL / 'docs/移动端云部署修复交付-20260920.md')
    copy(ROOT / 'docs/手机端打包说明.md', FULL / 'docs/手机端打包说明.md')
    (FULL / 'docs/README.md').write_text('当前版本以《移动端云部署修复交付-20260920.md》及《手机端打包说明.md》为准。其他测试文档、操作手册及构建报告保留旧版历史记录。现有服务器请使用单独的增量更新包。\n', encoding='utf-8')
    with zipfile.ZipFile(FULL / 'mobile' / f'h5-{VERSION}.zip', 'w', zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted((payload / 'h5').rglob('*')):
            if p.is_file(): bundle.write(p, p.relative_to(payload / 'h5').as_posix())
    archive(PATCH)
    archive(FULL)


if __name__ == '__main__': main()
