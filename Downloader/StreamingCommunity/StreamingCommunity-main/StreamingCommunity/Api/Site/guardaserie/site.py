# 09.06.24


# External libraries
from bs4 import BeautifulSoup
from rich.console import Console


# Internal utilities
from StreamingCommunity.Util.headers import get_userAgent
from StreamingCommunity.Util.http_client import create_client
from StreamingCommunity.Util.table import TVShowManager


# Logic class
from StreamingCommunity.Api.Template.config_loader import site_constant
from StreamingCommunity.Api.Template.Class.SearchType import MediaManager


# Variable
console = Console()
media_search_manager = MediaManager()
table_show_manager = TVShowManager()


def title_search(query: str) -> int:
    """
    Search for titles based on a search query.

    Parameters:
        - query (str): The query to search for.

    Returns:
        - int: The number of titles found.
    """
    media_search_manager.clear()
    table_show_manager.clear()

    search_url = f"{site_constant.FULL_URL}/?story={query}&do=search&subaction=search"
    console.print(f"[cyan]Search url: [yellow]{search_url}")

    try:
        response = create_client(headers={'user-agent': get_userAgent()}).get(search_url)
        response.raise_for_status()
    except Exception as e:
        console.print(f"[red]Site: {site_constant.SITE_NAME}, request search error: {e}")
        return 0

    # Create soup and find table
    soup = BeautifulSoup(response.text, "html.parser")

    # Try new structure (mlnh-thumb) first, then old (entry)
    result_divs = soup.find_all('div', class_='mlnh-thumb')
    if not result_divs:
        result_divs = soup.find_all('div', class_='entry')
    
    for serie_div in result_divs:
        try:
            a_tag = serie_div.find('a')
            if not a_tag:
                continue
            title = a_tag.get("title", "")
            if not title:
                continue
            title = title.replace("streaming guardaserie", "").strip()
            url = a_tag.get("href", "")
            if not url:
                continue
                
            img_tag = serie_div.find('img')
            img_src = ""
            if img_tag:
                img_src = img_tag.get('src', '')
                if img_src and not img_src.startswith('http'):
                    img_src = f"{site_constant.FULL_URL}/{img_src}"
            
            serie_info = {
                'name': title,
                'url': url,
                'type': 'tv',
                'image': img_src,
            }
            media_search_manager.add_media(serie_info)

        except Exception as e:
            print(f"Error parsing a film entry: {e}")

    # Return the number of titles found
    return media_search_manager.get_length()