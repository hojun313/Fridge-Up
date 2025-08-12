package com.cookandroid.cochef // 본인의 패키지 이름으로 변경하세요

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable // 클릭 가능한 아이템을 위해 추가
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight // 텍스트 스타일 조정을 위해 추가
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // 텍스트 크기 조정을 위해 추가
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cookandroid.cochef.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// BuildConfig 에 주입된 Gemini API 키 (빈 문자열이면 미설정)
private val API_KEY: String by lazy { BuildConfig.GEMINI_API_KEY }

// SharedPreferences 파일 이름 및 데이터 키 상수
private const val PREFS_NAME = "refrigerator_prefs"
private const val INGREDIENT_LIST_KEY = "ingredient_list"

// 재료 정보를 담는 데이터 클래스 (기존과 동일)
data class Ingredient(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val expirationDate: LocalDate
)

// AI가 추천한 요리 목록 아이템을 위한 데이터 클래스 (요약 정보)
data class RecipeSummary(
    val title: String, // 요리 이름
    val ingredients: List<String> // 이 요리에 사용되는 재료 목록 (사용자의 재료 중)
)

// UI 상태를 나타내는 sealed interface 변경
sealed interface RefrigeratorUiState {
    object Idle : RefrigeratorUiState // 초기 상태 또는 대기 상태
    object LoadingRecipes : RefrigeratorUiState // AI 추천 요리 목록 로딩 중
    data class RecipesLoaded(val recipes: List<RecipeSummary>) : RefrigeratorUiState // 요리 목록 로딩 성공
    object LoadingDetailedRecipe : RefrigeratorUiState // 선택된 요리의 상세 레시피 로딩 중
    data class DetailedRecipeLoaded(val recipe: String) : RefrigeratorUiState // 상세 레시피 로딩 성공 및 레시피 반환
    data class Error(val message: String) : RefrigeratorUiState // 오류 발생
}

// LocalDate를 위한 Gson TypeAdapter (기존과 동일)
class LocalDateAdapter : TypeAdapter<LocalDate>() {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    override fun write(out: JsonWriter, value: LocalDate?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.format(formatter))
        }
    }

    override fun read(input: JsonReader): LocalDate? {
        return if (input.peek() == com.google.gson.stream.JsonToken.NULL) {
            input.nextNull()
            null
        } else {
            val dateString = input.nextString()
            try {
                LocalDate.parse(dateString, formatter)
            } catch (e: DateTimeParseException) {
                println("Error parsing date string: $dateString")
                null
            }
        }
    }
}


// 냉장고 재료 관리 및 레시피 추천 ViewModel
class RefrigeratorViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = getApplication<Application>().applicationContext
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Gson 인스턴스 생성 시 LocalDateAdapter 등록 (기존과 동일)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
        .create()

    // 재료 목록을 관리하는 MutableStateFlow (기존과 동일)
    private val _ingredients = MutableStateFlow<List<Ingredient>>(emptyList())
    val ingredients: StateFlow<List<Ingredient>> = _ingredients.asStateFlow()

    // AI 추천 상태를 관리하는 MutableStateFlow (UI 상태 변경)
    private val _uiState = MutableStateFlow<RefrigeratorUiState>(RefrigeratorUiState.Idle)
    val uiState: StateFlow<RefrigeratorUiState> = _uiState.asStateFlow()

    // GenerativeModel 초기화 (기존과 동일)
    private lateinit var generativeModel: GenerativeModel

    init {
        loadIngredients()

        if (API_KEY == "YOUR_API_KEY" || API_KEY.isBlank()) {
            _uiState.value = RefrigeratorUiState.Error("API 키가 설정되지 않았습니다. API_KEY 변수를 확인해주세요.")
            println("AI Model Initialization Error: API Key is not set.")
        } else {
            try {
                generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash-latest",
                    apiKey = API_KEY
                )
            } catch (e: Exception) {
                _uiState.value = RefrigeratorUiState.Error("AI 모델 초기화 실패: ${e.message}")
                println("AI Model Initialization Error: ${e.message}")
            }
        }
    }

    // 재료 추가 함수 (기존과 동일)
    fun addIngredient(name: String, expirationDate: LocalDate) {
        val newIngredient = Ingredient(name = name, expirationDate = expirationDate)
        _ingredients.update { currentList ->
            val updatedList = currentList + newIngredient
            sortIngredients(updatedList)
        }
        saveIngredients(_ingredients.value)
    }

    // 재료 삭제 함수 (기존과 동일)
    fun removeIngredient(ingredient: Ingredient) {
        _ingredients.update { currentList ->
            val updatedList = currentList.filter { it.id != ingredient.id }
            sortIngredients(updatedList)
        }
        saveIngredients(_ingredients.value)
        // 재료 목록 변경 시 AI 추천 결과 초기화 (선택 사항)
        if (_uiState.value !is RefrigeratorUiState.Idle) {
            _uiState.value = RefrigeratorUiState.Idle
        }
    }

    // 재료 목록 정렬 함수 (기존과 동일)
    private fun sortIngredients(list: List<Ingredient>): List<Ingredient> {
        val today = LocalDate.now()
        val (expired, notExpired) = list.partition { it.expirationDate.isBefore(today) }
        val sortedExpired = expired.sortedBy { it.expirationDate }
        val sortedNotExpired = notExpired.sortedBy {
            ChronoUnit.DAYS.between(today, it.expirationDate)
        }
        return sortedExpired + sortedNotExpired
    }

    // 재료 목록 저장 함수 (기존과 동일)
    private fun saveIngredients(list: List<Ingredient>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(list)
                sharedPreferences.edit().putString(INGREDIENT_LIST_KEY, json).apply()
                println("Ingredients saved successfully.")
            } catch (e: Exception) {
                println("Error saving ingredients: ${e.message}")
            }
        }
    }

    // 재료 목록 불러오기 함수 (기존과 동일)
    private fun loadIngredients() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = sharedPreferences.getString(INGREDIENT_LIST_KEY, null)
                if (json != null) {
                    val type = object : TypeToken<List<Ingredient>>() {}.type
                    val loadedList: List<Ingredient> = gson.fromJson(json, type)
                    _ingredients.update { sortIngredients(loadedList) }
                    println("Ingredients loaded successfully.")
                } else {
                    println("No saved ingredients found.")
                }
            } catch (e: Exception) {
                println("Error loading ingredients: ${e.message}")
            }
        }
    }


    // AI 레시피 목록 추천 요청 함수 (로직 변경)
    fun requestRecipeListRecommendation() {
        if (API_KEY == "YOUR_API_KEY" || API_KEY.isBlank() || !this::generativeModel.isInitialized) {
            if (uiState.value !is RefrigeratorUiState.Error) {
                _uiState.value = RefrigeratorUiState.Error("AI 모델이 초기화되지 않았거나 API 키가 설정되지 않았습니다.")
            }
            println("Recipe list recommendation failed: API Key not set or model not initialized.")
            return
        }

        if (_ingredients.value.isEmpty()) {
            _uiState.value = RefrigeratorUiState.Error("재료가 없습니다. 재료를 먼저 추가해주세요.")
            println("Recipe list recommendation failed: Ingredient list is empty.")
            return
        }

        _uiState.value = RefrigeratorUiState.LoadingRecipes // 요리 목록 로딩 상태 시작
        println("Requesting recipe list recommendation...")

        viewModelScope.launch {
            try {
                val ingredientsListString = _ingredients.value.joinToString(", ") { it.name }

                // AI에게 요리 목록과 사용 재료를 JSON 형식으로 요청하는 프롬프트
                val prompt = """
                다음은 제가 가지고 있는 재료 목록입니다: $ingredientsListString

                이 재료들을 활용하여 만들 수 있는 요리 목록을 5개 이내로 추천해 주세요.
                각 요리에 대해 다음 정보를 포함하여 JSON 배열 형식으로 응답해 주세요.
                각 JSON 객체는 "title" (요리 이름, 문자열)과 "ingredients" (해당 요리에 사용되는 재료 목록 중 사용자의 재료 목록에 있는 재료들만, 문자열 배열) 필드를 가져야 합니다.
                다른 설명이나 추가 텍스트 없이 순수 JSON 배열만 반환해야 합니다.

                예시 JSON 형식:
                [
                  {
                    "title": "김치찌개",
                    "ingredients": ["김치", "돼지고기", "두부"]
                  },
                  {
                    "title": "계란말이",
                    "ingredients": ["계란", "당근", "파"]
                  }
                ]
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)

                response.text?.let { jsonResponse ->
                    try {
                        // AI 응답에서 JSON 문자열만 추출 (AI가 가끔 JSON 외 텍스트를 포함할 수 있음)
                        val jsonString = extractJsonArray(jsonResponse)
                        if (jsonString != null) {
                            // Gson을 사용하여 JSON 문자열을 List<RecipeSummary>로 파싱
                            val type = object : TypeToken<List<RecipeSummary>>() {}.type
                            val recipeList: List<RecipeSummary> = gson.fromJson(jsonString, type)

                            if (recipeList.isNotEmpty()) {
                                _uiState.value = RefrigeratorUiState.RecipesLoaded(recipeList) // 요리 목록 로딩 성공 상태
                                println("Recipe list recommendation successful: ${recipeList.size} recipes loaded.")
                            } else {
                                _uiState.value = RefrigeratorUiState.Error("추천할 요리를 찾지 못했습니다.")
                                println("Recipe list recommendation failed: No recipes found in response.")
                            }
                        } else {
                            _uiState.value = RefrigeratorUiState.Error("AI 응답 형식 오류: JSON 데이터를 찾을 수 없습니다.")
                            println("Recipe list recommendation failed: Could not extract JSON from response: $jsonResponse")
                        }
                    } catch (jsonError: JsonSyntaxException) {
                        // JSON 파싱 오류 처리
                        _uiState.value = RefrigeratorUiState.Error("AI 응답 파싱 오류: ${jsonError.message}")
                        println("Recipe list JSON parsing error: ${jsonError.message}\nResponse: $jsonResponse")
                    } catch (e: Exception) {
                        // 기타 파싱 또는 처리 오류
                        _uiState.value = RefrigeratorUiState.Error("AI 응답 처리 중 오류 발생: ${e.message}")
                        println("Recipe list processing error: ${e.message}\nResponse: $jsonResponse")
                    }

                } ?: run {
                    _uiState.value = RefrigeratorUiState.Error("AI가 요리 목록을 생성하지 못했습니다.")
                    println("Recipe list recommendation failed: No text response from AI.")
                }

            } catch (e: Exception) {
                _uiState.value = RefrigeratorUiState.Error("요리 목록 추천 중 오류 발생: ${e.message}")
                println("Recipe list Recommendation Error: ${e.message}")
            }
        }
    }

    // AI 응답 텍스트에서 JSON 배열 부분만 추출하는 헬퍼 함수
    private fun extractJsonArray(text: String): String? {
        val startIndex = text.indexOf('[')
        val endIndex = text.lastIndexOf(']')

        return if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            text.substring(startIndex, endIndex + 1)
        } else {
            null // JSON 배열 시작/끝을 찾지 못함
        }
    }


    // 사용자가 요리 목록에서 특정 요리를 선택했을 때 상세 레시피를 요청하는 함수
    fun selectRecipe(recipeTitle: String) {
        if (API_KEY == "YOUR_API_KEY" || API_KEY.isBlank() || !this::generativeModel.isInitialized) {
            _uiState.value = RefrigeratorUiState.Error("AI 모델이 초기화되지 않았거나 API 키가 설정되지 않았습니다.")
            println("Detailed recipe request failed: API Key not set or model not initialized.")
            return
        }

        _uiState.value = RefrigeratorUiState.LoadingDetailedRecipe // 상세 레시피 로딩 상태 시작
        println("Requesting detailed recipe for: $recipeTitle")

        viewModelScope.launch {
            try {
                val ingredientsListString = _ingredients.value.joinToString(", ") { it.name }

                // AI에게 특정 요리의 상세 레시피를 요청하는 프롬프트
                val prompt = """
                제가 가지고 있는 재료는 다음과 같습니다: $ingredientsListString

                이 재료들을 활용하여 "$recipeTitle" 요리의 상세한 레시피와 조리 방법을 알려주세요.
                필요한 추가 재료가 있다면 명시해 주세요.
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)

                response.text?.let { detailedRecipeText ->
                    _uiState.value = RefrigeratorUiState.DetailedRecipeLoaded(detailedRecipeText) // 상세 레시피 로딩 성공 상태
                    println("Detailed recipe loaded successfully for: $recipeTitle")
                } ?: run {
                    _uiState.value = RefrigeratorUiState.Error("AI가 상세 레시피를 생성하지 못했습니다.")
                    println("Detailed recipe request failed: No text response from AI for $recipeTitle.")
                }

            } catch (e: Exception) {
                _uiState.value = RefrigeratorUiState.Error("상세 레시피 추천 중 오류 발생: ${e.message}")
                println("Detailed Recipe Recommendation Error for $recipeTitle: ${e.message}")
            }
        }
    }

    // UI 상태를 초기 상태로 리셋하는 함수 (예: 재료 목록으로 돌아가기)
    fun resetUiState() {
        _uiState.value = RefrigeratorUiState.Idle
        println("UI state reset to Idle.")
    }
}

// 메인 냉장고 관리 화면 Composable
@Composable
fun RefrigeratorScreen(
    refrigeratorViewModel: RefrigeratorViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as Application))
) {
    // ViewModel의 상태들을 관찰
    val ingredientList by refrigeratorViewModel.ingredients.collectAsState()
    val uiState by refrigeratorViewModel.uiState.collectAsState()

    // 재료 이름 입력 필드 상태
    var ingredientName by remember { mutableStateOf("") }
    // 유통기한 입력 필드 상태 (yyyy-mm-dd 형식으로 가정)
    var expirationDateText by remember { mutableStateOf("") }
    // 유효하지 않은 날짜 입력 시 오류 메시지 상태
    var dateError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "냉장고 재료 관리",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 재료 입력 및 목록 영역 (AI 추천 결과에 따라 가변적으로 표시)
        when (uiState) {
            RefrigeratorUiState.Idle,
            is RefrigeratorUiState.Error, // 오류 시에도 재료 관리는 가능하도록
            is RefrigeratorUiState.LoadingRecipes, // 로딩 중에도 목록 자체는 표시 (선택 사항)
                -> {
                // 재료 추가 입력 필드 및 버튼
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = ingredientName,
                        onValueChange = { ingredientName = it },
                        label = { Text("재료 이름") },
                        modifier = Modifier.weight(1f)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = expirationDateText,
                            onValueChange = {
                                expirationDateText = it
                                dateError = false // 입력 변경 시 오류 상태 초기화
                            },
                            label = { Text("유통기한 (yyyy-mm-dd)") },
                            isError = dateError // 오류 상태에 따라 UI 변경
                        )
                        if (dateError) {
                            Text(
                                text = "유효하지 않은 날짜 형식",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Button(
                        onClick = {
                            try {
                                val date = LocalDate.parse(expirationDateText)
                                refrigeratorViewModel.addIngredient(ingredientName, date)
                                ingredientName = ""
                                expirationDateText = ""
                                dateError = false
                            } catch (e: DateTimeParseException) {
                                dateError = true
                                println("날짜 파싱 오류: ${e.message}")
                            } catch (e: Exception) {
                                println("재료 추가 중 오류: ${e.message}")
                            }
                        },
                        enabled = ingredientName.isNotBlank() && expirationDateText.isNotBlank()
                    ) {
                        Text("추가")
                    }
                }

                // 재료 목록 표시
                LazyColumn(
                    modifier = Modifier
                        .weight(1f) // 남은 공간 모두 차지
                        .fillMaxWidth()
                ) {
                    items(items = ingredientList, key = { it.id }) { ingredient ->
                        IngredientItem(ingredient = ingredient, onRemoveClick = {
                            refrigeratorViewModel.removeIngredient(it)
                        })
                    }
                }

                // AI 레시피 추천 버튼 (목록 로딩 중에는 비활성화)
                Button(
                    onClick = { refrigeratorViewModel.requestRecipeListRecommendation() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    enabled = uiState !is RefrigeratorUiState.LoadingRecipes &&
                            uiState !is RefrigeratorUiState.LoadingDetailedRecipe && // 상세 레시피 로딩 중에도 비활성화
                            uiState !is RefrigeratorUiState.DetailedRecipeLoaded // 상세 레시피 표시 중에도 비활성화
                    // Idle 또는 Error, RecipesLoaded 상태에서만 활성화
                ) {
                    Text("음식 추천 받기")
                }
            }
            is RefrigeratorUiState.RecipesLoaded -> {
                // 요리 목록 로딩 성공 시 목록 표시
                val recipes = (uiState as RefrigeratorUiState.RecipesLoaded).recipes
                Text(
                    text = "추천 요리 목록:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f) // 남은 공간 모두 차지
                        .fillMaxWidth()
                ) {
                    items(items = recipes, key = { it.title }) { recipeSummary ->
                        // 각 요리 요약 아이템 Composable (클릭 가능)
                        RecipeSummaryItem(
                            recipe = recipeSummary,
                            onRecipeClick = { title ->
                                refrigeratorViewModel.selectRecipe(title) // 클릭 시 상세 레시피 요청
                            }
                        )
                    }
                }
                // 목록 상태에서 재료 목록으로 돌아가는 버튼 추가 (선택 사항)
                Button(
                    onClick = { refrigeratorViewModel.resetUiState() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text("재료 목록으로 돌아가기")
                }
            }
            is RefrigeratorUiState.LoadingDetailedRecipe -> {
                // 상세 레시피 로딩 중
                Box( // Box를 사용하여 로딩 인디케이터를 중앙에 배치
                    modifier = Modifier
                        .weight(1f) // 남은 공간 모두 차지
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("상세 레시피 불러오는 중...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                // 이 상태에서는 다른 버튼 비활성화
            }
            is RefrigeratorUiState.DetailedRecipeLoaded -> {
                // 상세 레시피 로딩 성공 시 레시피 표시
                val recipe = (uiState as RefrigeratorUiState.DetailedRecipeLoaded).recipe
                Text(
                    text = "상세 레시피:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // 상세 레시피는 스크롤 가능한 컬럼에 표시
                LazyColumn(
                    modifier = Modifier
                        .weight(1f) // 남은 공간 모두 차지
                        .fillMaxWidth()
                ) {
                    item { // Text 하나를 LazyColumn의 item으로 추가
                        Text(
                            text = recipe,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // 상세 레시피 상태에서 요리 목록으로 돌아가는 버튼 추가
                Button(
                    onClick = { refrigeratorViewModel.requestRecipeListRecommendation() }, // 요리 목록 상태로 돌아가기
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text("다른 요리 추천 보기")
                }
                // 또는 처음 상태로 돌아가는 버튼
                Button(
                    onClick = { refrigeratorViewModel.resetUiState() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp) // 버튼 간 간격
                ) {
                    Text("재료 목록으로 돌아가기")
                }
            }
            RefrigeratorUiState.LoadingRecipes -> {
                // 요리 목록 로딩 중 (재료 목록 위에 로딩 인디케이터 표시)
                // 재료 목록과 입력 필드는 계속 표시하되, 로딩 중임을 알림
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = ingredientName,
                        onValueChange = { ingredientName = it },
                        label = { Text("재료 이름") },
                        modifier = Modifier.weight(1f),
                        enabled = false // 로딩 중 입력 필드 비활성화
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = expirationDateText,
                            onValueChange = {
                                expirationDateText = it
                                dateError = false
                            },
                            label = { Text("유통기한 (yyyy-mm-dd)") },
                            isError = dateError,
                            enabled = false // 로딩 중 입력 필드 비활성화
                        )
                        if (dateError) {
                            Text(
                                text = "유효하지 않은 날짜 형식",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Button(onClick = {}, enabled = false) { Text("추가") } // 로딩 중 버튼 비활성화
                }

                // 재료 목록 표시 (로딩 중에도 표시)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(items = ingredientList, key = { it.id }) { ingredient ->
                        IngredientItem(ingredient = ingredient, onRemoveClick = {}) // 로딩 중 삭제 비활성화
                    }
                }

                // 로딩 인디케이터 표시
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp))
                Text("추천 요리 목록 불러오는 중...", modifier = Modifier.align(Alignment.CenterHorizontally))

                // AI 레시피 추천 버튼 (로딩 중 비활성화)
                Button(
                    onClick = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    enabled = false
                ) {
                    Text("음식 추천 받기")
                }
            }
        }

        // 오류 메시지 표시 (어떤 상태에서든 오류가 발생하면 표시)
        if (uiState is RefrigeratorUiState.Error) {
            val errorMessage = (uiState as RefrigeratorUiState.Error).message
            Text(
                text = "오류: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


// 개별 재료 목록 아이템을 표시하는 Composable (기존과 동일)
@Composable
fun IngredientItem(ingredient: Ingredient, onRemoveClick: (Ingredient) -> Unit) {
    val today = remember { LocalDate.now() }
    val isExpired = ingredient.expirationDate.isBefore(today)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = if (isExpired) Color(0xFFFFF9C4) else Color.Transparent // 연한 노란색
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = ingredient.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "유통기한: ${ingredient.expirationDate}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isExpired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = { onRemoveClick(ingredient) }) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "삭제",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Divider()
}

// AI 추천 요리 목록의 각 요리 아이템을 표시하는 Composable
@Composable
fun RecipeSummaryItem(recipe: RecipeSummary, onRecipeClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRecipeClick(recipe.title) } // 클릭 시 ViewModel 함수 호출
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        Text(
            text = recipe.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold // 요리 이름 강조
        )
        Spacer(modifier = Modifier.height(4.dp)) // 간격 추가
        Text(
            text = "사용 재료: ${recipe.ingredients.joinToString(", ")}", // 사용 재료 목록 표시
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, // 부가 정보 색상
            fontSize = 12.sp // 글씨 크기 조정
        )
    }
    Divider() // 구분선
}


// Preview를 위한 Composable (기존과 동일)
@Composable
@Preview(showBackground = true)
fun PreviewRefrigeratorScreen() {
    // Preview에서는 실제 ViewModel 대신 더미 데이터를 사용하는 것이 좋습니다.
    // 복잡한 ViewModel 상태 변화를 미리보기 하려면 PreviewParameterProvider 등을 사용할 수 있습니다.
    // 여기서는 기본 상태의 화면만 미리 봅니다.
    RefrigeratorScreen()
}