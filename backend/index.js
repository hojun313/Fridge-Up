
const express = require('express');
require('dotenv').config();
const cors = require('cors');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const fs = require('fs').promises;
const path = require('path');

const app = express();
// Vercel / Railway 등 배포 환경 포트 지원
const port = process.env.PORT || 3001;

// CORS 설정 (필요시 origin 화이트리스트로 교체)
app.use(cors({
  origin: process.env.CORS_ORIGIN ? process.env.CORS_ORIGIN.split(',') : '*',
  methods: ['GET','POST','DELETE','OPTIONS'],
  allowedHeaders: ['Content-Type','Authorization']
}));

// OPTIONS 요청에 대한 명시적 처리 (CORS preflight)
app.options('*', cors());

app.use(express.json());

// 데이터베이스 파일 경로 (서버리스 환경 대비: /tmp 사용 고려 가능)
const DB_PATH = path.join(__dirname, 'db.json');

// Helper function to read the database
const readDB = async () => {
  try {
    const data = await fs.readFile(DB_PATH, 'utf8');
    const db = JSON.parse(data);
    // db가 객체이고 ingredients 속성이 배열인지 확인
    if (db && typeof db === 'object' && Array.isArray(db.ingredients)) {
      return db;
    }
    // 구조가 올바르지 않으면 기본 구조 반환
    return { ingredients: [] };
  } catch (error) {
    // 파일이 없거나 JSON 파싱 오류 시 기본 구조 반환
    if (error.code === 'ENOENT' || error instanceof SyntaxError) {
        return { ingredients: [] };
    }
    // 그 외 다른 오류는 전파
    throw error;
  }
};

// Helper function to write to the database
const writeDB = async (data) => {
  await fs.writeFile(DB_PATH, JSON.stringify(data, null, 2), 'utf8');
};

// --- 재료 API 엔드포인트 ---

// 모든 재료 가져오기 (GET /api/ingredients)
app.get('/api/ingredients', async (req, res) => {
  try {
    const db = await readDB();
    res.json(db.ingredients || []);
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: 'Failed to read ingredients.' });
  }
});

// 새 재료 추가하기 (POST /api/ingredients)
app.post('/api/ingredients', async (req, res) => {
  try {
    const { name, expiryDate } = req.body;
    if (!name || !expiryDate) {
      return res.status(400).json({ error: 'Ingredient name and expiry date are required.' });
    }

    const db = await readDB();
    const newIngredient = {
      id: Date.now(), // 간단한 고유 ID 생성
      name,
      expiryDate,
    };

    db.ingredients.push(newIngredient);
    await writeDB(db);

    res.status(201).json(newIngredient);
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: 'Failed to add ingredient.' });
  }
});

// 재료 삭제하기 (DELETE /api/ingredients/:id)
app.delete('/api/ingredients/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const db = await readDB();

    const initialLength = db.ingredients.length;
    db.ingredients = db.ingredients.filter(ing => ing.id !== parseInt(id));

    if (db.ingredients.length === initialLength) {
        return res.status(404).json({ error: 'Ingredient not found.' });
    }

    await writeDB(db);
    res.status(204).send(); // 성공적으로 삭제되었으나 내용은 없음
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: 'Failed to delete ingredient.' });
  }
});


// --- 레시피 추천 API ---
let genAI = null;
if (process.env.API_KEY) {
  try {
    genAI = new GoogleGenerativeAI(process.env.API_KEY);
  } catch (e) {
    console.error('Generative AI 초기화 실패:', e.message);
  }
} else {
  console.warn('경고: API_KEY 환경변수가 설정되지 않았습니다. /api/recommend 사용 불가.');
}

app.post('/api/recommend', async (req, res) => {
  if (!genAI) {
    return res.status(503).json({ error: 'AI 기능이 비활성화되었습니다. (API_KEY 미설정)' });
  }
  try {
    const db = await readDB();
    const ingredients = (db.ingredients || []).map(ing => ing.name);
    if (!ingredients.length) {
      return res.status(400).json({ recommendation: '냉장고에 재료가 없어요. 먼저 재료를 추가해주세요!' });
    }
    const model = genAI.getGenerativeModel({ model: process.env.GEMINI_MODEL || 'gemini-1.5-flash' });
    const prompt = `현재 냉장고에 있는 재료: ${ingredients.join(', ')}\n이 재료들을 활용할 수 있는 요리 1~3개와 간단한 설명, 필요한 추가 재료(있다면)를 제시하세요.`;
    const result = await model.generateContent(prompt);
    const response = await result.response;
    const text = response.text();
    res.json({ recommendation: text });
  } catch (error) {
    console.error('AI 추천 생성 중 오류:', error);
    res.status(500).json({ error: '레시피를 추천받는 데 실패했습니다.' });
  }
});

// 헬스 체크 & 환경 상태 노출 (민감 정보 제외)
app.get('/healthz', (req, res) => {
  res.json({
    ok: true,
    aiEnabled: !!genAI,
    timestamp: new Date().toISOString()
  });
});

if (require.main === module) {
  app.listen(port, () => {
    console.log(`Server listening on :${port}`);
  });
}

module.exports = app;
