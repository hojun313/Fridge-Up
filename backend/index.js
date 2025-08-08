
const express = require('express');
require('dotenv').config();
const cors = require('cors');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const fs = require('fs').promises; // 파일 시스템 모듈 추가
const path = require('path'); // 경로 모듈 추가

const app = express();
const port = 3001;

// CORS 설정 - 모든 도메인 허용
app.use(cors());

// OPTIONS 요청에 대한 명시적 처리 (CORS preflight)
app.options('*', cors());

app.use(express.json());

// 데이터베이스 파일 경로
const DB_PATH = path.join(__dirname, 'db.json');

// Helper function to read the database
const readDB = async () => {
  try {
    const data = await fs.readFile(DB_PATH, 'utf8');
    return JSON.parse(data);
  } catch (error) {
    // 파일이 없거나 내용이 비어있으면 기본 구조 반환
    if (error.code === 'ENOENT' || error.message.includes('Unexpected end of JSON input')) {
        return { ingredients: [] };
    }
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


// --- 레시피 추천 API --- (수정됨)
const genAI = new GoogleGenerativeAI(process.env.API_KEY);

app.post('/api/recommend', async (req, res) => {
  try {
    // 이제 DB에서 재료 목록을 가져옴
    const db = await readDB();
    const ingredients = db.ingredients.map(ing => ing.name);

    if (!ingredients || ingredients.length === 0) {
      return res.status(400).json({ recommendation: '냉장고에 재료가 없어요. 먼저 재료를 추가해주세요!' });
    }

    const model = genAI.getGenerativeModel({ model: 'gemini-1.5-flash' });

    const prompt = `현재 냉장고에 있는 재료는 다음과 같습니다: ${ingredients.join(', ')}. 이 재료들을 활용해서 만들 수 있는 요리 레시피를 추천해주세요. 각 레시피는 이름, 간단한 설명, 필요한 재료 목록, 그리고 조리 순서로 구성해주세요.`;

    const result = await model.generateContent(prompt);
    const response = await result.response;
    const text = response.text();

    res.json({ recommendation: text });
  } catch (error) {
    console.error('AI 추천 생성 중 오류:', error);
    res.status(500).json({ error: '레시피를 추천받는 데 실패했습니다.' });
  }
});

app.listen(port, () => {
  console.log(`Server is running on http://localhost:${port}`);
});
