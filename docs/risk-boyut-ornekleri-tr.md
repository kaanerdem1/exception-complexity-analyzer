# **Risk boyutu örnek skorları **  
**Eşik sırası: dikkat / yüksek / kritik.**

Alt skor bantları (yaklaşık):

| Bant | Aralık |
|------|--------|
| Dikkat eşiğine kadar | ~0–29 |
| Dikkat → yüksek | ~30–49 |
| Yüksek → kritik | ~50–79 |
| Kritiğin üstü | ~80–100 |

Nihai metod skoru: `0,67 × (en yüksek alt skor) + 0,33 × (ağırlıklı ortalama)`

---

## Dallanma (CC)

`if`, `else`, `for`, `while`, `catch`, `&&`, `||`, switch kolu vb.

**Eşikler:** 4 / 10 / 24

| Ham değer (CC) | Alt skor (yaklaşık) |
|----------------|---------------------|
| 1 | ~7 |
| 4 | ~29 (dikkat tavanı) |
| 7 | ~40 |
| 10 | ~49 (yüksek tavanı) |
| 17 | ~65 |
| 24 | ~79 (kritik tavanı) |
| 40+ | 80–100 bandı |

---

## Kod satırı (LOC / NCSS)

Yorum ve boş satır hariç.

**Eşikler:** 17 / 36 / 84

| Ham değer (satır) | Alt skor (yaklaşık) |
|-------------------|---------------------|
| 8 | ~14 |
| 17 | ~29 |
| 25 | ~38 |
| 36 | ~49 |
| 60 | ~65 |
| 84 | ~79 |
| 150+ | 80+ bandı |

---

## İç içe derinlik

`if` / `for` / `try` içinde kaç kat.

**Eşikler:** 2 / 4 / 5

| Ham değer (derinlik) | Alt skor (yaklaşık) |
|----------------------|---------------------|
| 1 | ~15 |
| 2 | ~29 |
| 3 | ~40 |
| 4 | ~49 |
| 5 | ~79 |
| 6+ | 80+ |

---

## Parametre sayısı

**Eşikler:** 2 / 4 / 6

| Ham değer (parametre) | Alt skor (yaklaşık) |
|-----------------------|---------------------|
| 1 | ~15 |
| 2 | ~29 |
| 3 | ~40 |
| 4 | ~49 |
| 5–6 | ~65–79 |
| 7+ | 80+ |

---

## Bilişsel karmaşıklık (cognitive)

**Eşikler:** 5 / 18 / 47

| Ham değer (cognitive) | Alt skor (yaklaşık) |
|-----------------------|---------------------|
| 3 | ~17 |
| 5 | ~29 |
| 12 | ~40 |
| 18 | ~49 |
| 30 | ~65 |
| 47 | ~79 |
