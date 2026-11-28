import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('gate',Path(__file__).with_name('evaluate-population.py'))
gate=importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class GateTests(unittest.TestCase):
    def fixture(self, root, name, seed, candidate=90, refs=None, repeats=2, samples=100):
        folder=root/name; folder.mkdir()
        settings={'refs':refs or {'baseline':'old','candidate':'new'},'counts':[300],'ticks':2400,'seed':seed,'repeats':repeats}
        (folder/'settings.json').write_text(json.dumps(settings))
        rows=[]
        for i in (1,2):
            for version in ('baseline','candidate'):
                rows.append(dict(version=version,repeat=i,site='0, 65, 0',spawned=300,ticks=2400,samples=100 if version=='baseline' else samples,mean_sampled_mspt=100 if version=='baseline' else candidate,p95_sampled_mspt=120,mean_sampled_heap_mib=1000,gone=0,errors=0))
        path=folder/'comparison.csv'
        with path.open('w',newline='') as f:
            w=csv.DictWriter(f,fieldnames=rows[0].keys());w.writeheader();w.writerows(rows)
        return path
    def test_pass_and_hold(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            a=self.fixture(root,'a','seed1')
            b=self.fixture(root,'b','seed2',candidate=99)
            r=gate.evaluate([a,b]); self.assertFalse(r[0][-1]); self.assertTrue(r[1][-1])
    def test_duplicate_seed(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            with self.assertRaises(ValueError): gate.evaluate([self.fixture(root,'a','same'),self.fixture(root,'b','same')])
    def test_mixed_refs(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            with self.assertRaises(ValueError): gate.evaluate([self.fixture(root,'a','one'),self.fixture(root,'b','two',refs={'baseline':'old','candidate':'other'})])
    def test_incomplete(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError): gate.evaluate([self.fixture(Path(d),'a','one',repeats=3)])
    def test_sample_mismatch(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError): gate.evaluate([self.fixture(Path(d),'a','one',samples=99)])

if __name__=='__main__': unittest.main()
