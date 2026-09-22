import importlib.util
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('nginx_patch', ROOT / 'scripts/patch-nginx-mobile.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class DeploymentTests(unittest.TestCase):
    def test_both_backend_images_include_backup_client(self):
        for name in ('Dockerfile', 'Dockerfile.release'):
            with self.subTest(image=name):
                dockerfile = (ROOT/'backend'/name).read_text(encoding='utf-8')
                self.assertIn('default-mysql-client', dockerfile)

    def test_patch_preserves_ssl_and_domains_and_is_idempotent(self):
        text = 'map $http_upgrade $connection { default upgrade; }\nserver { listen 80; location / { try_files $uri /index.html; } }\nserver { listen 443 ssl; ssl_certificate /cert/server.crt; server_name m.example.com; location / { proxy_pass http://mobile-web; } }'
        updated = module.patch(text)
        self.assertEqual(updated.count('location = /actuator/health'), 2)
        self.assertEqual(updated.count('client_max_body_size 10m'), 2)
        self.assertIn('ssl_certificate /cert/server.crt;', updated)
        self.assertIn('server_name m.example.com;', updated)
        self.assertEqual(module.patch(updated), updated)

    def test_health_proxy_and_upload_size_exist_in_both_images(self):
        for frontend in ('admin-web', 'mobile'):
            text = (ROOT/frontend/'nginx.conf').read_text()
            self.assertIn('location = /actuator/health', text)
            self.assertIn('client_max_body_size 10m', text)
            self.assertIn('http://127.0.0.1/', (ROOT/frontend/'Dockerfile.release').read_text())
        app = (ROOT/'backend/src/main/resources/application.yml').read_text()
        self.assertIn('max-file-size: 5MB', app)
        self.assertIn('max-request-size: 10MB', app)

    def test_fresh_schema_contains_all_handover_columns(self):
        schema = (ROOT/'db/schema.sql').read_text(encoding='utf-8-sig')
        for table in ('sales_order', 'processing_order'):
            ddl = next(line for line in schema.splitlines() if line.startswith(f'CREATE TABLE IF NOT EXISTS {table} ('))
            self.assertIn('handover TINYINT NOT NULL DEFAULT 0', ddl)
            if table == 'processing_order': self.assertIn('handover_time DATETIME NULL', ddl)

if __name__ == '__main__': unittest.main()
