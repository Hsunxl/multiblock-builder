import gzip
import struct
import unittest
from import_guide import NbtReader, parse_nbt, convert


class ImportTests(unittest.TestCase):
    def template(self):
        return {'size':[2,1,1],'palette':[{'Name':'minecraft:stone'},{'Name':'minecraft:air'}], 'blocks':[{'pos':[0,0,0],'state':0,'nbt':{'Items':['do not copy']}},{'pos':[1,0,0],'state':1}]}

    def test_air_and_nbt_are_removed(self):
        result, stripped = convert(self.template(),'example')
        self.assertFalse(result['survival'])
        self.assertEqual(len(result['blocks']),1)
        self.assertEqual(stripped,1)
        self.assertNotIn('nbt',result['blocks'][0])

    def test_bounds_and_duplicate(self):
        n=self.template(); n['blocks'][0]['pos']=[-1,0,0]
        with self.assertRaises(ValueError): convert(n,'x')
        n=self.template(); n['blocks'].append(n['blocks'][0])
        with self.assertRaises(ValueError): convert(n,'x')

    def test_missing_palette(self):
        n=self.template(); del n['palette']
        with self.assertRaises(ValueError): convert(n,'x')

    def test_invalid_state_index(self):
        n=self.template(); n['blocks'][0]['state']=-1
        with self.assertRaises(ValueError): convert(n,'x')

    def test_nbt_reader(self):
        data=b'\x0a\x00\x00\x03\x00\x01x'+struct.pack('>i',123)+b'\x00'
        self.assertEqual(parse_nbt(data),{'x':123})
        self.assertEqual(parse_nbt(gzip.compress(data)),{'x':123})
        for invalid in (data[:-1],data+b'x',b'\x00',b''):
            with self.assertRaises(ValueError): parse_nbt(invalid)

    def test_size_limit(self):
        n=self.template(); n['size']=[65,1,1]
        with self.assertRaises(ValueError): convert(n,'x')


if __name__=='__main__': unittest.main()
