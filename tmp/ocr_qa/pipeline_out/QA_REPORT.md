# Live OCR QA — first + last
**Model:** mistral-ocr-4  
**When:** 2026-08-04T00:27:24

## First page — **100%** (10/10)
- Blocks: 5 · latency: Nones
- [x] date
- [x] grade
- [x] subject
- [x] name_raw_shaab_or_shoaib
- [x] name_fixed_shoaib
- [x] doctor
- [x] primary_school
- [x] urdu_word
- [x] pakistan
- [x] arabic_present

### Preview (typo-fixed)
```
Date: 30/7/26
Grade: 5
Subject: English

My self
My name is Shoaib (معي) I am
24 years old. I read in
class Five. I study in the
Government Primary School in
my town. My favorite subject
is Urdu. I want to be
a doctor in future. I like
to play video games in
my free Time. I love Pakistan
Army.

صبر المحارف

معاون : ادو
تاريخ : 30-07-2026

صبر نام شعيب - صبري خضر 24 سال - صبري
با خوض ماست صبري يمر صبري يمر
القول صبري يمر صبري يمر - صبري يمر صبري يمر
صبري يمر يمر - صبري يمر صبري يمر
صبري يمر يمر - صبري يمر صبري يمر
```

- `/home/olufsen/ocr/tmp/ocr_qa/pipeline_out/first_clean.jpg`
- `/home/olufsen/ocr/tmp/ocr_qa/pipeline_out/first_side_by_side.jpg`

## Last page — structure **100%** (7/7)
- Blocks: 1 · plan_detected: **True** · canon: **True**
- [x] looks_like_plan
- [x] has_task
- [x] has_owners
- [x] has_parallel
- [x] has_mapping
- [x] has_regression
- [x] canon_engine

### Preview (raw OCR)
```
Birech DMD
Task -> Data fetching, Translation, Mapping.
Owners Hatay (day 1) Shoaib (2 days) Hatay (3 days)
Main App (Shoaib)
Dependencies
Plan (UI)
web App (QA overall)
Data fetching (QA)
Translation (QA required) here
Mapping (QA)
1- Data fetching [1]
2- Parallel -> Translation [2]
Mapping [2, 3, 4]
3- Translation (Q2A) (Mahnour) [3]
Mapping (QAA) (IQRA) [5]
[3, 7] web App (Regression Testing whole by team member)
```

- `/home/olufsen/ocr/tmp/ocr_qa/pipeline_out/last_clean.jpg`
- `/home/olufsen/ocr/tmp/ocr_qa/pipeline_out/last_side_by_side.jpg`
- `/home/olufsen/ocr/tmp/ocr_qa/pipeline_out/last_triple_compare.jpg`
