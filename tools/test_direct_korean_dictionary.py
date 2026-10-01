import json
import unittest
from direct_korean_dictionary import direct_records

class DirectKoreanTests(unittest.TestCase):
    def test_native_gloss_pos_and_exact_headword(self):
        rows = [{"word": "preliminary", "lang_code": "en", "pos": "adj",
                 "senses": [{"glosses": ["예비의, 준비의."]}]}]
        self.assertEqual({"preliminary": ["[형용사] 예비의, 준비의."]},
                         direct_records(map(json.dumps, rows)))

    def test_no_reverse_substring_or_fake_korean(self):
        rows = [{"word": "preliminary research", "lang_code": "en", "pos": "noun",
                 "senses": [{"glosses": ["예비 조사"]}]},
                {"word": "example", "lang_code": "en", "pos": "noun",
                 "senses": [{"glosses": ["English gloss only", "ㄱㄴ"]}]},
                {"word": "important", "lang_code": "fr", "pos": "adj",
                 "senses": [{"glosses": ["잘못된 언어"]}]}]
        result = direct_records(map(json.dumps, rows))
        self.assertEqual({"preliminary research": ["[명사] 예비 조사"]}, result)

    def test_unknown_pos_not_guessed_from_ambiguous_categories(self):
        rows = [{"word": "necessary", "lang_code": "en", "pos": "unknown",
                 "categories": [{"name": "영어 명사"}, {"name": "영어 형용사"}],
                 "senses": [{"glosses": ["필요한"]}]}]
        self.assertEqual({"necessary": ["필요한"]}, direct_records(map(json.dumps, rows)))

if __name__ == "__main__":
    unittest.main()
