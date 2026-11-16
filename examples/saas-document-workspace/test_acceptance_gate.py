# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import copy,unittest
from verify import validate
class EvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.good={'status':'passed','globalErrors':0,'expected':list(range(29)),
            'results':[{'id':f'E{i:02}','status':'passed','errors':0,'usecases':[f'S{(i-1)%20+1:02}']} for i in range(1,30)]}
    def test_complete_evidence(self):validate(self.good)
    def test_incomplete_or_false_green_is_refused(self):
        for mutation in ('missing','duplicate','skipped','failure','runner','usecase','selection'):
            with self.subTest(mutation=mutation):
                r=copy.deepcopy(self.good)
                if mutation=='missing':r['results'].pop()
                if mutation=='duplicate':r['results'][-1]['id']='E01'
                if mutation=='skipped':r['results'][0]['status']='skipped'
                if mutation=='failure':r['results'][0]['errors']=1
                if mutation=='runner':r['globalErrors']=1
                if mutation=='usecase':r['results'][19]['usecases']=[]
                if mutation=='selection':r['expected'].pop()
                with self.assertRaises(ValueError):validate(r)
if __name__=='__main__':unittest.main()
