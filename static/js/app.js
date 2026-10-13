async function fetchSystemStatus() {
    const apiInput = document.getElementById('apiInput');
    apiInput.value = window.location.href;

    document.getElementById('copyApiBtn').addEventListener('click', () => {
        apiInput.select();
        document.execCommand('copy');
        const btn = document.getElementById('copyApiBtn');
        btn.textContent = 'Copied!';
        setTimeout(() => {
            btn.textContent = 'Copy';
        }, 2000);
    });

    try {
        const response = await fetch(window.location.href + '/api/status');
        const data = await response.json();

        const statusElement = document.getElementById('db-status');
        const tableList = document.getElementById('table-list');
        const errorMessage = document.getElementById('error-message');

        tableList.innerHTML = ''; 

        if (data.status === 'online') {
            statusElement.textContent = 'ONLINE 🟢';
            statusElement.parentNode.style.display = "none";
            tableList.style.display = "block";
            // Populate Tables
            if (data.tables.length === 0) {
                tableList.innerHTML = '<li><i>No tables created yet.</i></li>';
            } else {
                data.tables.forEach(table => {
                    const li = document.createElement('li');
                    li.textContent = '📊 ' + table;
                    tableList.appendChild(li);
                });
            }

        } else {
            statusElement.textContent = 'OFFLINE 🔴';
            statusElement.parentNode.style.display = "block";
            tableList.style.display = "none";
            errorMessage.textContent = 'Database Error: ' + data.error;
            errorMessage.style.display = 'block';
        }
    } catch (error) {
        document.getElementById('db-status').textContent = 'API UNREACHABLE 🔴';
        document.getElementById('table-list').innerHTML = '';
        document.getElementById('db-status').parentNode.style.display = "block";
        document.getElementById('table-list').style.display = "none";
    }
}
document.addEventListener("DOMContentLoaded", () => {
    fetchSystemStatus();
});